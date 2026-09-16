package testutil

import "testing"

func TestProxyHost(t *testing.T) {
	tests := []struct {
		name     string
		endpoint string
		want     string
	}{
		{
			name:     "http endpoint with port",
			endpoint: "http://localhost:4566",
			want:     "localhost",
		},
		{
			name:     "https endpoint with port",
			endpoint: "https://example.test:8443",
			want:     "example.test",
		},
		{
			name:     "ipv6 endpoint with port",
			endpoint: "http://[::1]:4566",
			want:     "::1",
		},
		{
			name:     "endpoint with path",
			endpoint: "https://example.test:4566/floci",
			want:     "example.test",
		},
		{
			name:     "endpoint without scheme",
			endpoint: "localhost:4566",
			want:     "localhost",
		},
		{
			name:     "invalid endpoint",
			endpoint: "http://[::1",
			want:     "http://[::1",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv("FLOCI_ENDPOINT", tt.endpoint)

			if got := ProxyHost(); got != tt.want {
				t.Fatalf("ProxyHost() = %q, want %q", got, tt.want)
			}
		})
	}
}
