package tests

import (
	"bytes"
	"context"
	"net/url"
	"strings"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	s3types "github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestS3(t *testing.T) {
	ctx := context.Background()
	svc := testutil.S3Client()
	bucket := "go-test-bucket"
	key := "test-object.json"
	content := `{"source":"go-test"}`

	// Cleanup at end
	t.Cleanup(func() {
		svc.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: aws.String(key)})
		svc.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: aws.String("copy-" + key)})
		svc.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(bucket)})
	})

	t.Run("CreateBucket", func(t *testing.T) {
		_, err := svc.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(bucket)})
		require.NoError(t, err)
	})

	t.Run("ListBuckets", func(t *testing.T) {
		r, err := svc.ListBuckets(ctx, &s3.ListBucketsInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Buckets)
	})

	t.Run("PutObject", func(t *testing.T) {
		_, err := svc.PutObject(ctx, &s3.PutObjectInput{
			Bucket:      aws.String(bucket),
			Key:         aws.String(key),
			Body:        strings.NewReader(content),
			ContentType: aws.String("application/json"),
		})
		require.NoError(t, err)
	})

	t.Run("GetObject", func(t *testing.T) {
		out, err := svc.GetObject(ctx, &s3.GetObjectInput{
			Bucket: aws.String(bucket),
			Key:    aws.String(key),
		})
		require.NoError(t, err)
		defer out.Body.Close()

		var buf bytes.Buffer
		buf.ReadFrom(out.Body)
		assert.Equal(t, content, buf.String())
	})

	t.Run("HeadObject", func(t *testing.T) {
		head, err := svc.HeadObject(ctx, &s3.HeadObjectInput{
			Bucket: aws.String(bucket),
			Key:    aws.String(key),
		})
		require.NoError(t, err)
		assert.NotNil(t, head.LastModified)
		assert.Zero(t, head.LastModified.Nanosecond(), "LastModified should have second precision")
	})

	t.Run("ListObjectsV2", func(t *testing.T) {
		r, err := svc.ListObjectsV2(ctx, &s3.ListObjectsV2Input{Bucket: aws.String(bucket)})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Contents)
	})

	t.Run("CopyObject", func(t *testing.T) {
		_, err := svc.CopyObject(ctx, &s3.CopyObjectInput{
			Bucket:     aws.String(bucket),
			CopySource: aws.String(bucket + "/" + key),
			Key:        aws.String("copy-" + key),
		})
		require.NoError(t, err)
	})

	t.Run("DeleteObject", func(t *testing.T) {
		_, err := svc.DeleteObject(ctx, &s3.DeleteObjectInput{
			Bucket: aws.String(bucket),
			Key:    aws.String(key),
		})
		require.NoError(t, err)
	})

	t.Run("DeleteBucket", func(t *testing.T) {
		// First delete the copy
		svc.DeleteObject(ctx, &s3.DeleteObjectInput{
			Bucket: aws.String(bucket),
			Key:    aws.String("copy-" + key),
		})

		_, err := svc.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(bucket)})
		require.NoError(t, err)
	})
}

func TestS3LocationConstraint(t *testing.T) {
	ctx := context.Background()
	svc := testutil.S3Client()
	euBucket := "go-test-bucket-eu"

	t.Cleanup(func() {
		svc.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(euBucket)})
	})

	t.Run("CreateBucketWithLocationConstraint", func(t *testing.T) {
		_, err := svc.CreateBucket(ctx, &s3.CreateBucketInput{
			Bucket: aws.String(euBucket),
			CreateBucketConfiguration: &s3types.CreateBucketConfiguration{
				LocationConstraint: s3types.BucketLocationConstraintEuCentral1,
			},
		})
		require.NoError(t, err)
	})

	t.Run("GetBucketLocation", func(t *testing.T) {
		loc, err := svc.GetBucketLocation(ctx, &s3.GetBucketLocationInput{Bucket: aws.String(euBucket)})
		require.NoError(t, err)
		assert.Equal(t, "eu-central-1", string(loc.LocationConstraint))
	})
}

// TestS3NonASCIIKey tests CopyObject with non-ASCII (multibyte) keys.
// Regression test for issue #93.
func TestS3NonASCIIKey(t *testing.T) {
	ctx := context.Background()
	svc := testutil.S3Client()
	bucket := "go-test-non-ascii"
	srcKey := "src/テスト画像.png"
	dstKey := "dst/テスト画像.png"

	t.Cleanup(func() {
		svc.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: aws.String(srcKey)})
		svc.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: aws.String(dstKey)})
		svc.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(bucket)})
	})

	// Setup
	_, err := svc.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(bucket)})
	require.NoError(t, err)

	_, err = svc.PutObject(ctx, &s3.PutObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(srcKey),
		Body:   strings.NewReader("non-ascii content"),
	})
	require.NoError(t, err)

	// Copy with non-ASCII key
	_, err = svc.CopyObject(ctx, &s3.CopyObjectInput{
		Bucket:     aws.String(bucket),
		CopySource: aws.String(bucket + "/" + url.PathEscape(srcKey)),
		Key:        aws.String(dstKey),
	})
	require.NoError(t, err, "CopyObject with non-ASCII key should succeed")

	// Verify the copy exists
	_, err = svc.HeadObject(ctx, &s3.HeadObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(dstKey),
	})
	require.NoError(t, err, "copied object should exist")
}

// TestS3MultipartCopyNonASCIIKey exercises UploadPartCopy with a URL-encoded
// non-ASCII source key to cover the multipart copy code path.
func TestS3MultipartCopyNonASCIIKey(t *testing.T) {
	ctx := context.Background()
	svc := testutil.S3Client()
	bucket := "go-test-multipart-copy-non-ascii"
	srcKey := "src/テスト画像.bin"
	dstKey := "dst/テスト画像.bin"
	content := bytes.Repeat([]byte("a"), 6*1024*1024)
	var uploadID string

	t.Cleanup(func() {
		if uploadID != "" {
			svc.AbortMultipartUpload(ctx, &s3.AbortMultipartUploadInput{
				Bucket:   aws.String(bucket),
				Key:      aws.String(dstKey),
				UploadId: aws.String(uploadID),
			})
		}
		svc.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: aws.String(srcKey)})
		svc.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: aws.String(dstKey)})
		svc.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(bucket)})
	})

	_, err := svc.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(bucket)})
	require.NoError(t, err)

	_, err = svc.PutObject(ctx, &s3.PutObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(srcKey),
		Body:   bytes.NewReader(content),
	})
	require.NoError(t, err)

	createOut, err := svc.CreateMultipartUpload(ctx, &s3.CreateMultipartUploadInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(dstKey),
	})
	require.NoError(t, err)
	uploadID = aws.ToString(createOut.UploadId)
	require.NotEmpty(t, uploadID)

	partOut, err := svc.UploadPartCopy(ctx, &s3.UploadPartCopyInput{
		Bucket:     aws.String(bucket),
		Key:        aws.String(dstKey),
		UploadId:   aws.String(uploadID),
		PartNumber: aws.Int32(1),
		CopySource: aws.String(bucket + "/" + url.PathEscape(srcKey)),
	})
	require.NoError(t, err, "UploadPartCopy with non-ASCII key should succeed")
	require.NotNil(t, partOut.CopyPartResult)
	require.NotNil(t, partOut.CopyPartResult.ETag)

	_, err = svc.CompleteMultipartUpload(ctx, &s3.CompleteMultipartUploadInput{
		Bucket:   aws.String(bucket),
		Key:      aws.String(dstKey),
		UploadId: aws.String(uploadID),
		MultipartUpload: &s3types.CompletedMultipartUpload{
			Parts: []s3types.CompletedPart{
				{
					ETag:       partOut.CopyPartResult.ETag,
					PartNumber: aws.Int32(1),
				},
			},
		},
	})
	require.NoError(t, err)
	uploadID = ""

	getOut, err := svc.GetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(dstKey),
	})
	require.NoError(t, err)
	defer getOut.Body.Close()

	var copied bytes.Buffer
	_, err = copied.ReadFrom(getOut.Body)
	require.NoError(t, err)
	assert.Equal(t, content, copied.Bytes())
}

// TestS3LargeObject tests uploading a 25 MB object.
// Validates upload size limit handling.
func TestS3LargeObject(t *testing.T) {
	ctx := context.Background()
	svc := testutil.S3Client()
	bucket := "go-test-large-upload"
	key := "large-object-25mb.bin"
	size := int64(25 * 1024 * 1024) // 25 MB

	t.Cleanup(func() {
		svc.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: aws.String(key)})
		svc.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(bucket)})
	})

	// Setup
	_, err := svc.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(bucket)})
	require.NoError(t, err)

	// Create 25 MB payload
	payload := make([]byte, size)

	_, err = svc.PutObject(ctx, &s3.PutObjectInput{
		Bucket:        aws.String(bucket),
		Key:           aws.String(key),
		Body:          bytes.NewReader(payload),
		ContentType:   aws.String("application/octet-stream"),
		ContentLength: aws.Int64(size),
	})
	require.NoError(t, err, "PutObject 25 MB should succeed")

	// Verify content-length via HeadObject
	head, err := svc.HeadObject(ctx, &s3.HeadObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(key),
	})
	require.NoError(t, err)
	assert.Equal(t, size, aws.ToInt64(head.ContentLength), "content-length should be 25 MB")
}
