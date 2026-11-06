"""S3 CORS enforcement integration tests."""

import logging
import urllib.request
import urllib.error

import pytest

logger = logging.getLogger(__name__)


class TestS3CORS:
    """Test S3 CORS enforcement."""

    @pytest.fixture(autouse=True)
    def setup_bucket(self, s3_client, unique_name, endpoint_url):
        """Set up a test bucket with an object for CORS testing."""
        self.bucket = f"pytest-cors-{unique_name}"
        self.endpoint = endpoint_url
        self.s3 = s3_client

        s3_client.create_bucket(Bucket=self.bucket)
        s3_client.put_object(
            Bucket=self.bucket,
            Key="cors-test.txt",
            Body=b"hello cors",
            ContentType="text/plain",
        )

        yield

        # Cleanup
        try:
            s3_client.delete_bucket_cors(Bucket=self.bucket)
        except Exception as e:
            logger.warning("Failed to clean up CORS config for bucket %s: %s", self.bucket, e)
        try:
            s3_client.delete_object(Bucket=self.bucket, Key="cors-test.txt")
            s3_client.delete_bucket(Bucket=self.bucket)
        except Exception as e:
            logger.warning("Failed to clean up S3 bucket %s: %s", self.bucket, e)

    def _raw_request(self, method, path, headers=None):
        """Make a raw HTTP request; returns (status_code, lowercase_headers_dict)."""
        url = f"{self.endpoint}/{self.bucket}{path}"
        req = urllib.request.Request(url, method=method)
        if headers:
            for k, v in headers.items():
                req.add_header(k, v)
        try:
            with urllib.request.urlopen(req) as resp:
                return resp.status, {k.lower(): v for k, v in resp.headers.items()}
        except urllib.error.HTTPError as e:
            return e.code, {k.lower(): v for k, v in e.headers.items()}

    def test_preflight_without_cors_config_returns_403(self):
        """Test preflight request without CORS config returns 403."""
        status, _ = self._raw_request(
            "OPTIONS",
            "/cors-test.txt",
            {"Origin": "http://localhost:3000", "Access-Control-Request-Method": "GET"},
        )
        assert status == 403

    def test_put_bucket_cors_wildcard(self):
        """Test PutBucketCors with wildcard origin."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["*"],
                        "AllowedMethods": ["GET", "PUT", "POST", "DELETE", "HEAD"],
                        "AllowedHeaders": ["*"],
                        "ExposeHeaders": ["ETag"],
                        "MaxAgeSeconds": 3000,
                    }
                ]
            },
        )

    def test_wildcard_preflight_returns_200(self):
        """Test wildcard CORS preflight returns 200."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["*"],
                        "AllowedMethods": ["GET", "PUT", "POST", "DELETE", "HEAD"],
                        "AllowedHeaders": ["*"],
                        "ExposeHeaders": ["ETag"],
                        "MaxAgeSeconds": 3000,
                    }
                ]
            },
        )

        status, headers = self._raw_request(
            "OPTIONS",
            "/cors-test.txt",
            {"Origin": "http://localhost:3000", "Access-Control-Request-Method": "GET"},
        )
        assert status == 200
        assert headers.get("access-control-allow-origin") == "*"
        assert headers.get("access-control-max-age") == "3000"
        assert "GET" in headers.get("access-control-allow-methods", "").upper()

    def test_wildcard_actual_get_returns_cors_headers(self):
        """Test actual GET with Origin header returns CORS headers."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["*"],
                        "AllowedMethods": ["GET"],
                        "AllowedHeaders": ["*"],
                        "ExposeHeaders": ["ETag"],
                        "MaxAgeSeconds": 3000,
                    }
                ]
            },
        )

        status, headers = self._raw_request(
            "GET", "/cors-test.txt", {"Origin": "http://localhost:3000"}
        )
        assert headers.get("access-control-allow-origin") == "*"
        vary = headers.get("vary", "")
        assert any(t.strip().lower() == "origin" for t in vary.split(","))
        assert "ETag" in headers.get("access-control-expose-headers", "")

    def test_get_without_origin_has_no_cors_headers(self):
        """Test GET without Origin header has no CORS headers."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["*"],
                        "AllowedMethods": ["GET"],
                        "AllowedHeaders": ["*"],
                    }
                ]
            },
        )

        _, headers = self._raw_request("GET", "/cors-test.txt")
        assert "access-control-allow-origin" not in headers

    def test_options_without_origin_has_no_cors_headers(self):
        """Test OPTIONS without Origin header has no CORS headers."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["*"],
                        "AllowedMethods": ["GET"],
                        "AllowedHeaders": ["*"],
                    }
                ]
            },
        )

        _, headers = self._raw_request("OPTIONS", "/cors-test.txt")
        assert "access-control-allow-origin" not in headers

    def test_specific_origin_echoes_origin(self):
        """Test specific origin CORS config echoes the origin."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["https://example.com"],
                        "AllowedMethods": ["GET", "PUT"],
                        "AllowedHeaders": ["Content-Type", "Authorization"],
                        "ExposeHeaders": ["ETag", "x-amz-request-id"],
                        "MaxAgeSeconds": 600,
                    }
                ]
            },
        )

        status, headers = self._raw_request(
            "OPTIONS",
            "/cors-test.txt",
            {
                "Origin": "https://example.com",
                "Access-Control-Request-Method": "GET",
                "Access-Control-Request-Headers": "Content-Type",
            },
        )
        assert status == 200
        assert headers.get("access-control-allow-origin") == "https://example.com"
        assert headers.get("access-control-max-age") == "600"

    def test_non_matching_origin_returns_403(self):
        """Test non-matching origin returns 403."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["https://example.com"],
                        "AllowedMethods": ["GET"],
                        "AllowedHeaders": ["*"],
                    }
                ]
            },
        )

        status, _ = self._raw_request(
            "OPTIONS",
            "/cors-test.txt",
            {"Origin": "https://attacker.evil.com", "Access-Control-Request-Method": "GET"},
        )
        assert status == 403

    def test_non_matching_method_returns_403(self):
        """Test non-matching method returns 403."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["https://example.com"],
                        "AllowedMethods": ["GET", "PUT"],
                        "AllowedHeaders": ["*"],
                    }
                ]
            },
        )

        status, _ = self._raw_request(
            "OPTIONS",
            "/cors-test.txt",
            {"Origin": "https://example.com", "Access-Control-Request-Method": "DELETE"},
        )
        assert status == 403

    def test_delete_bucket_cors(self):
        """Test DeleteBucketCors restores 403 on preflight."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["*"],
                        "AllowedMethods": ["GET"],
                        "AllowedHeaders": ["*"],
                    }
                ]
            },
        )

        self.s3.delete_bucket_cors(Bucket=self.bucket)

        status, _ = self._raw_request(
            "OPTIONS",
            "/cors-test.txt",
            {"Origin": "http://localhost:3000", "Access-Control-Request-Method": "GET"},
        )
        assert status == 403

    def test_subdomain_wildcard_matches(self):
        """Test subdomain wildcard pattern matches subdomains."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["http://*.example.com"],
                        "AllowedMethods": ["GET"],
                        "AllowedHeaders": ["*"],
                        "MaxAgeSeconds": 120,
                    }
                ]
            },
        )

        status, headers = self._raw_request(
            "OPTIONS",
            "/cors-test.txt",
            {"Origin": "http://app.example.com", "Access-Control-Request-Method": "GET"},
        )
        assert status == 200
        assert headers.get("access-control-allow-origin") == "http://app.example.com"

    def test_subdomain_wildcard_rejects_wrong_scheme(self):
        """Test subdomain wildcard rejects different scheme."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["http://*.example.com"],
                        "AllowedMethods": ["GET"],
                        "AllowedHeaders": ["*"],
                    }
                ]
            },
        )

        status, _ = self._raw_request(
            "OPTIONS",
            "/cors-test.txt",
            {"Origin": "https://app.example.com", "Access-Control-Request-Method": "GET"},
        )
        assert status == 403

    def test_subdomain_wildcard_rejects_different_domain(self):
        """Test subdomain wildcard rejects different domain."""
        self.s3.put_bucket_cors(
            Bucket=self.bucket,
            CORSConfiguration={
                "CORSRules": [
                    {
                        "AllowedOrigins": ["http://*.example.com"],
                        "AllowedMethods": ["GET"],
                        "AllowedHeaders": ["*"],
                    }
                ]
            },
        )

        status, _ = self._raw_request(
            "OPTIONS",
            "/cors-test.txt",
            {"Origin": "http://app.other.com", "Access-Control-Request-Method": "GET"},
        )
        assert status == 403
