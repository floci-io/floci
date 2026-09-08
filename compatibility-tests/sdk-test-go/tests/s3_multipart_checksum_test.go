package tests

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"hash/crc32"
	"io"
	"math/rand"
	"strings"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/s3/manager"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	s3types "github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// Multipart uploads through the S3 transfer manager, the code path terraform-provider-aws uses for
// aws_s3_object. S3 stores a COMPOSITE checksum for SHA algorithms (the algorithm applied to the
// concatenated part checksums, suffixed with "-<parts>") and reports it on HeadObject, without the
// suffix on GetObjectAttributes. A copy is written as a single object with a FULL_OBJECT checksum.

const checksumPartSize = 5 * 1024 * 1024

func checksumPayload(size int) []byte {
	b := make([]byte, size)
	rand.New(rand.NewSource(20260903)).Read(b)
	return b
}

func splitParts(data []byte, size int) [][]byte {
	var parts [][]byte
	for start := 0; start < len(data); start += size {
		end := start + size
		if end > len(data) {
			end = len(data)
		}
		parts = append(parts, data[start:end])
	}
	return parts
}

func sha256B64(data []byte) string {
	d := sha256.Sum256(data)
	return base64.StdEncoding.EncodeToString(d[:])
}

func sha256Composite(parts [][]byte) string {
	var joined []byte
	for _, p := range parts {
		d := sha256.Sum256(p)
		joined = append(joined, d[:]...)
	}
	return sha256B64(joined) + fmt.Sprintf("-%d", len(parts))
}

func crc32Bytes(data []byte) []byte {
	v := crc32.ChecksumIEEE(data)
	return []byte{byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v)}
}

func crc32Composite(parts [][]byte) string {
	var joined []byte
	for _, p := range parts {
		joined = append(joined, crc32Bytes(p)...)
	}
	return base64.StdEncoding.EncodeToString(crc32Bytes(joined)) + fmt.Sprintf("-%d", len(parts))
}

// crc64Nvme implements CRC-64/NVME (reflected polynomial 0x9a6c9329ac4bc9b5), the checksum S3
// attaches when a client declares no algorithm.
func crc64Nvme(data []byte) []byte {
	var table [256]uint64
	for i := range table {
		c := uint64(i)
		for j := 0; j < 8; j++ {
			if c&1 == 1 {
				c = (c >> 1) ^ 0x9a6c9329ac4bc9b5
			} else {
				c >>= 1
			}
		}
		table[i] = c
	}
	c := ^uint64(0)
	for _, b := range data {
		c = table[byte(c)^b] ^ (c >> 8)
	}
	c = ^c
	out := make([]byte, 8)
	for i := 0; i < 8; i++ {
		out[i] = byte(c >> (56 - 8*i))
	}
	return out
}

func TestS3MultipartChecksums(t *testing.T) {
	ctx := context.Background()
	svc := testutil.S3Client()
	bucket := "go-test-multipart-checksums"
	payload := checksumPayload(12 * 1024 * 1024)
	parts := splitParts(payload, checksumPartSize)
	require.Len(t, parts, 3)
	uploader := manager.NewUploader(svc, func(u *manager.Uploader) {
		u.PartSize = checksumPartSize
	})
	keys := []string{"sha256.bin", "default.bin", "sha256-copy.bin"}

	_, err := svc.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(bucket)})
	require.NoError(t, err)
	t.Cleanup(func() {
		for _, key := range keys {
			if _, err := svc.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: aws.String(key)}); err != nil {
				t.Logf("cleanup: could not delete s3://%s/%s: %v", bucket, key, err)
			}
		}
		if _, err := svc.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(bucket)}); err != nil {
			t.Logf("cleanup: could not delete bucket %s: %v", bucket, err)
		}
	})

	head := func(t *testing.T, key string) *s3.HeadObjectOutput {
		out, err := svc.HeadObject(ctx, &s3.HeadObjectInput{
			Bucket: aws.String(bucket), Key: aws.String(key), ChecksumMode: s3types.ChecksumModeEnabled,
		})
		require.NoError(t, err)
		return out
	}

	t.Run("SHA256 upload reports the composite checksum", func(t *testing.T) {
		key := keys[0]
		_, err := uploader.Upload(ctx, &s3.PutObjectInput{
			Bucket: aws.String(bucket), Key: aws.String(key), Body: bytes.NewReader(payload),
			ChecksumAlgorithm: s3types.ChecksumAlgorithmSha256,
		})
		require.NoError(t, err)

		composite := sha256Composite(parts)
		h := head(t, key)
		assert.Equal(t, composite, aws.ToString(h.ChecksumSHA256))
		assert.Equal(t, s3types.ChecksumTypeComposite, h.ChecksumType)
		assert.True(t, strings.HasSuffix(aws.ToString(h.ETag), `-3"`), aws.ToString(h.ETag))

		attrs, err := svc.GetObjectAttributes(ctx, &s3.GetObjectAttributesInput{
			Bucket: aws.String(bucket), Key: aws.String(key),
			ObjectAttributes: []s3types.ObjectAttributes{
				s3types.ObjectAttributesChecksum, s3types.ObjectAttributesObjectParts, s3types.ObjectAttributesEtag,
			},
		})
		require.NoError(t, err)
		assert.Equal(t, strings.TrimSuffix(composite, "-3"), aws.ToString(attrs.Checksum.ChecksumSHA256),
			"GetObjectAttributes omits the part-count suffix")
		assert.Equal(t, s3types.ChecksumTypeComposite, attrs.Checksum.ChecksumType)
		assert.Equal(t, int32(3), aws.ToInt32(attrs.ObjectParts.TotalPartsCount))
		for i, part := range attrs.ObjectParts.Parts {
			assert.Equal(t, sha256B64(parts[i]), aws.ToString(part.ChecksumSHA256), "part %d", i+1)
		}
		assert.Equal(t, strings.Trim(aws.ToString(h.ETag), `"`), aws.ToString(attrs.ETag),
			"GetObjectAttributes returns the ETag without quotes")
	})

	t.Run("default checksum of the uploader matches what S3 stores", func(t *testing.T) {
		key := keys[1]
		_, err := uploader.Upload(ctx, &s3.PutObjectInput{
			Bucket: aws.String(bucket), Key: aws.String(key), Body: bytes.NewReader(payload),
		})
		require.NoError(t, err)

		// Which algorithm and type the SDK picks depends on its version; whatever it sent, the stored
		// value must be the one S3 would compute for that combination.
		h := head(t, key)
		switch {
		case h.ChecksumCRC32 != nil && h.ChecksumType == s3types.ChecksumTypeComposite:
			assert.Equal(t, crc32Composite(parts), aws.ToString(h.ChecksumCRC32))
		case h.ChecksumCRC32 != nil && h.ChecksumType == s3types.ChecksumTypeFullObject:
			assert.Equal(t, base64.StdEncoding.EncodeToString(crc32Bytes(payload)), aws.ToString(h.ChecksumCRC32))
		case h.ChecksumCRC64NVME != nil:
			assert.Equal(t, s3types.ChecksumTypeFullObject, h.ChecksumType)
			assert.Equal(t, base64.StdEncoding.EncodeToString(crc64Nvme(payload)), aws.ToString(h.ChecksumCRC64NVME))
		default:
			t.Fatalf("unexpected checksum on HeadObject: %+v", h)
		}
		t.Logf("uploader default: CRC32=%q CRC64NVME=%q type=%s",
			aws.ToString(h.ChecksumCRC32), aws.ToString(h.ChecksumCRC64NVME), h.ChecksumType)

		// Downloading with response checksum validation enabled must round-trip the payload.
		obj, err := svc.GetObject(ctx, &s3.GetObjectInput{
			Bucket: aws.String(bucket), Key: aws.String(key), ChecksumMode: s3types.ChecksumModeEnabled,
		})
		require.NoError(t, err)
		body, err := io.ReadAll(obj.Body)
		require.NoError(t, err)
		obj.Body.Close()
		assert.True(t, bytes.Equal(payload, body), "downloaded body differs from the payload")
	})

	t.Run("copy of the multipart object is a single full object", func(t *testing.T) {
		source := head(t, keys[0])
		out, err := svc.CopyObject(ctx, &s3.CopyObjectInput{
			Bucket: aws.String(bucket), Key: aws.String(keys[2]),
			CopySource: aws.String(bucket + "/" + keys[0]),
		})
		require.NoError(t, err)

		sum := md5.Sum(payload)
		expectedETag := `"` + hex.EncodeToString(sum[:]) + `"`
		assert.Equal(t, sha256B64(payload), aws.ToString(out.CopyObjectResult.ChecksumSHA256))
		assert.Equal(t, s3types.ChecksumTypeFullObject, out.CopyObjectResult.ChecksumType)
		assert.Equal(t, expectedETag, aws.ToString(out.CopyObjectResult.ETag))
		assert.NotEqual(t, aws.ToString(source.ETag), aws.ToString(out.CopyObjectResult.ETag))

		h := head(t, keys[2])
		assert.Equal(t, sha256B64(payload), aws.ToString(h.ChecksumSHA256))
		assert.Equal(t, s3types.ChecksumTypeFullObject, h.ChecksumType)
		assert.Equal(t, expectedETag, aws.ToString(h.ETag))
	})
}
