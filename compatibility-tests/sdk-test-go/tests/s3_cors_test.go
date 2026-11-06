package tests

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	s3types "github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestS3CORS(t *testing.T) {
	ctx := context.Background()
	svc := testutil.S3Client()
	baseURL := testutil.Endpoint()
	bucket := fmt.Sprintf("go-cors-test-%d", time.Now().UnixMilli())
	objectKey := "cors-test.txt"

	// raw sends a raw HTTP request and returns the status code and response headers.
	raw := func(method, path string, headers map[string]string) (int, http.Header, error) {
		rawURL := baseURL + "/" + bucket + path
		req, err := http.NewRequest(method, rawURL, nil)
		if err != nil {
			return 0, nil, err
		}
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		client := &http.Client{
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				return http.ErrUseLastResponse
			},
		}
		resp, err := client.Do(req)
		if err != nil {
			return 0, nil, err
		}
		defer resp.Body.Close()
		return resp.StatusCode, resp.Header, nil
	}

	varyHasOrigin := func(vary string) bool {
		for _, tok := range strings.Split(vary, ",") {
			if strings.EqualFold(strings.TrimSpace(tok), "origin") {
				return true
			}
		}
		return false
	}

	t.Cleanup(func() {
		svc.DeleteBucketCors(ctx, &s3.DeleteBucketCorsInput{Bucket: aws.String(bucket)})
		svc.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: aws.String(objectKey)})
		svc.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(bucket)})
	})

	// Setup
	t.Run("Setup", func(t *testing.T) {
		_, err := svc.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(bucket)})
		require.NoError(t, err)

		_, err = svc.PutObject(ctx, &s3.PutObjectInput{
			Bucket:      aws.String(bucket),
			Key:         aws.String(objectKey),
			Body:        strings.NewReader("hello cors"),
			ContentType: aws.String("text/plain"),
		})
		require.NoError(t, err)
	})

	t.Run("PreflightWithoutConfig_Returns403", func(t *testing.T) {
		status, _, err := raw("OPTIONS", "/"+objectKey, map[string]string{
			"Origin":                        "http://localhost:3000",
			"Access-Control-Request-Method": "GET",
		})
		require.NoError(t, err)
		assert.Equal(t, 403, status)
	})

	t.Run("WildcardCORS", func(t *testing.T) {
		maxAge := int32(3000)
		_, err := svc.PutBucketCors(ctx, &s3.PutBucketCorsInput{
			Bucket: aws.String(bucket),
			CORSConfiguration: &s3types.CORSConfiguration{
				CORSRules: []s3types.CORSRule{
					{
						AllowedOrigins: []string{"*"},
						AllowedMethods: []string{"GET", "PUT", "POST", "DELETE", "HEAD"},
						AllowedHeaders: []string{"*"},
						ExposeHeaders:  []string{"ETag"},
						MaxAgeSeconds:  &maxAge,
					},
				},
			},
		})
		require.NoError(t, err)

		// Preflight
		status, hdrs, err := raw("OPTIONS", "/"+objectKey, map[string]string{
			"Origin":                        "http://localhost:3000",
			"Access-Control-Request-Method": "GET",
		})
		require.NoError(t, err)
		assert.Equal(t, 200, status)
		assert.Equal(t, "*", hdrs.Get("Access-Control-Allow-Origin"))
		assert.Equal(t, "3000", hdrs.Get("Access-Control-Max-Age"))
		assert.Contains(t, strings.ToUpper(hdrs.Get("Access-Control-Allow-Methods")), "GET")

		// Actual GET with Origin
		status, hdrs, err = raw("GET", "/"+objectKey, map[string]string{"Origin": "http://localhost:3000"})
		require.NoError(t, err)
		assert.Equal(t, "*", hdrs.Get("Access-Control-Allow-Origin"))
		assert.True(t, varyHasOrigin(hdrs.Get("Vary")))
		assert.Contains(t, hdrs.Get("Access-Control-Expose-Headers"), "ETag")

		// GET without Origin - no CORS headers
		_, hdrs, err = raw("GET", "/"+objectKey, nil)
		require.NoError(t, err)
		assert.Empty(t, hdrs.Get("Access-Control-Allow-Origin"))

		// OPTIONS without Origin - no CORS headers
		_, hdrs, err = raw("OPTIONS", "/"+objectKey, nil)
		require.NoError(t, err)
		assert.Empty(t, hdrs.Get("Access-Control-Allow-Origin"))
	})

	t.Run("SpecificOriginCORS", func(t *testing.T) {
		maxAge := int32(600)
		_, err := svc.PutBucketCors(ctx, &s3.PutBucketCorsInput{
			Bucket: aws.String(bucket),
			CORSConfiguration: &s3types.CORSConfiguration{
				CORSRules: []s3types.CORSRule{
					{
						AllowedOrigins: []string{"https://example.com"},
						AllowedMethods: []string{"GET", "PUT"},
						AllowedHeaders: []string{"Content-Type", "Authorization"},
						ExposeHeaders:  []string{"ETag", "x-amz-request-id"},
						MaxAgeSeconds:  &maxAge,
					},
				},
			},
		})
		require.NoError(t, err)

		// Matching origin
		status, hdrs, err := raw("OPTIONS", "/"+objectKey, map[string]string{
			"Origin":                         "https://example.com",
			"Access-Control-Request-Method":  "GET",
			"Access-Control-Request-Headers": "Content-Type",
		})
		require.NoError(t, err)
		assert.Equal(t, 200, status)
		assert.Equal(t, "https://example.com", hdrs.Get("Access-Control-Allow-Origin"))
		assert.Equal(t, "600", hdrs.Get("Access-Control-Max-Age"))

		// Non-matching origin
		status, _, err = raw("OPTIONS", "/"+objectKey, map[string]string{
			"Origin":                        "https://attacker.evil.com",
			"Access-Control-Request-Method": "GET",
		})
		require.NoError(t, err)
		assert.Equal(t, 403, status)

		// Non-matching method
		status, _, err = raw("OPTIONS", "/"+objectKey, map[string]string{
			"Origin":                        "https://example.com",
			"Access-Control-Request-Method": "DELETE",
		})
		require.NoError(t, err)
		assert.Equal(t, 403, status)

		// Actual GET matching
		_, hdrs, err = raw("GET", "/"+objectKey, map[string]string{"Origin": "https://example.com"})
		require.NoError(t, err)
		assert.Equal(t, "https://example.com", hdrs.Get("Access-Control-Allow-Origin"))

		// Actual GET non-matching
		_, hdrs, err = raw("GET", "/"+objectKey, map[string]string{"Origin": "https://not-allowed.com"})
		require.NoError(t, err)
		assert.Empty(t, hdrs.Get("Access-Control-Allow-Origin"))
	})

	t.Run("DeleteBucketCors_PreflightReturns403", func(t *testing.T) {
		_, err := svc.DeleteBucketCors(ctx, &s3.DeleteBucketCorsInput{Bucket: aws.String(bucket)})
		require.NoError(t, err)

		status, _, err := raw("OPTIONS", "/"+objectKey, map[string]string{
			"Origin":                        "http://localhost:3000",
			"Access-Control-Request-Method": "GET",
		})
		require.NoError(t, err)
		assert.Equal(t, 403, status)
	})

	t.Run("SubdomainWildcardCORS", func(t *testing.T) {
		maxAge := int32(120)
		_, err := svc.PutBucketCors(ctx, &s3.PutBucketCorsInput{
			Bucket: aws.String(bucket),
			CORSConfiguration: &s3types.CORSConfiguration{
				CORSRules: []s3types.CORSRule{
					{
						AllowedOrigins: []string{"http://*.example.com"},
						AllowedMethods: []string{"GET"},
						AllowedHeaders: []string{"*"},
						MaxAgeSeconds:  &maxAge,
					},
				},
			},
		})
		require.NoError(t, err)

		// Matching subdomain
		status, hdrs, err := raw("OPTIONS", "/"+objectKey, map[string]string{
			"Origin":                        "http://app.example.com",
			"Access-Control-Request-Method": "GET",
		})
		require.NoError(t, err)
		assert.Equal(t, 200, status)
		assert.Equal(t, "http://app.example.com", hdrs.Get("Access-Control-Allow-Origin"))

		// Wrong scheme (https instead of http)
		status, _, err = raw("OPTIONS", "/"+objectKey, map[string]string{
			"Origin":                        "https://app.example.com",
			"Access-Control-Request-Method": "GET",
		})
		require.NoError(t, err)
		assert.Equal(t, 403, status)

		// Different domain
		status, _, err = raw("OPTIONS", "/"+objectKey, map[string]string{
			"Origin":                        "http://app.other.com",
			"Access-Control-Request-Method": "GET",
		})
		require.NoError(t, err)
		assert.Equal(t, 403, status)
	})
}
