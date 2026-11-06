package tests

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"strings"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/acm"
	acmtypes "github.com/aws/aws-sdk-go-v2/service/acm/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// generateSelfSignedCert creates a self-signed certificate and private key in PEM format.
func generateSelfSignedCert() (certPEM, keyPEM []byte) {
	key, _ := rsa.GenerateKey(rand.Reader, 2048)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "test.example.com"},
		NotBefore:    time.Now(),
		NotAfter:     time.Now().Add(365 * 24 * time.Hour),
	}
	certDER, _ := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	certPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER})
	keyPEM = pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	return
}

func TestACM(t *testing.T) {
	ctx := context.Background()
	svc := testutil.ACMClient()
	var certARN string

	t.Run("RequestCertificate", func(t *testing.T) {
		r, err := svc.RequestCertificate(ctx, &acm.RequestCertificateInput{
			DomainName: aws.String("go-test.example.com"),
		})
		require.NoError(t, err)
		certARN = aws.ToString(r.CertificateArn)
		assert.NotEmpty(t, certARN)
		assert.True(t, strings.HasPrefix(certARN, "arn:aws:acm:"), "ARN should match arn:aws:acm pattern, got: %s", certARN)
	})

	t.Run("DescribeCertificate", func(t *testing.T) {
		if certARN == "" {
			t.Skip("No certificate ARN from RequestCertificate")
		}
		r, err := svc.DescribeCertificate(ctx, &acm.DescribeCertificateInput{
			CertificateArn: aws.String(certARN),
		})
		require.NoError(t, err)
		assert.Equal(t, "go-test.example.com", aws.ToString(r.Certificate.DomainName))
		assert.Equal(t, acmtypes.CertificateStatusIssued, r.Certificate.Status)
	})

	t.Run("GetCertificate", func(t *testing.T) {
		if certARN == "" {
			t.Skip("No certificate ARN from RequestCertificate")
		}
		r, err := svc.GetCertificate(ctx, &acm.GetCertificateInput{
			CertificateArn: aws.String(certARN),
		})
		require.NoError(t, err)
		assert.NotEmpty(t, aws.ToString(r.Certificate))
	})

	t.Run("ListCertificates", func(t *testing.T) {
		if certARN == "" {
			t.Skip("No certificate ARN from RequestCertificate")
		}
		r, err := svc.ListCertificates(ctx, &acm.ListCertificatesInput{})
		require.NoError(t, err)

		found := false
		for _, c := range r.CertificateSummaryList {
			if aws.ToString(c.CertificateArn) == certARN {
				found = true
				break
			}
		}
		assert.True(t, found, "Certificate %s should appear in list", certARN)
	})

	t.Run("AddTagsToCertificate", func(t *testing.T) {
		if certARN == "" {
			t.Skip("No certificate ARN from RequestCertificate")
		}
		_, err := svc.AddTagsToCertificate(ctx, &acm.AddTagsToCertificateInput{
			CertificateArn: aws.String(certARN),
			Tags: []acmtypes.Tag{
				{Key: aws.String("Env"), Value: aws.String("test")},
			},
		})
		require.NoError(t, err)

		r, err := svc.ListTagsForCertificate(ctx, &acm.ListTagsForCertificateInput{
			CertificateArn: aws.String(certARN),
		})
		require.NoError(t, err)

		found := false
		for _, tag := range r.Tags {
			if aws.ToString(tag.Key) == "Env" && aws.ToString(tag.Value) == "test" {
				found = true
				break
			}
		}
		assert.True(t, found, "Tag Env=test should be present")
	})

	t.Run("ListTagsForCertificate", func(t *testing.T) {
		if certARN == "" {
			t.Skip("No certificate ARN from RequestCertificate")
		}
		r, err := svc.ListTagsForCertificate(ctx, &acm.ListTagsForCertificateInput{
			CertificateArn: aws.String(certARN),
		})
		require.NoError(t, err)

		found := false
		for _, tag := range r.Tags {
			if aws.ToString(tag.Key) == "Env" && aws.ToString(tag.Value) == "test" {
				found = true
				break
			}
		}
		assert.True(t, found, "Tag Env=test should still be present")
	})

	t.Run("RemoveTagsFromCertificate", func(t *testing.T) {
		if certARN == "" {
			t.Skip("No certificate ARN from RequestCertificate")
		}
		_, err := svc.RemoveTagsFromCertificate(ctx, &acm.RemoveTagsFromCertificateInput{
			CertificateArn: aws.String(certARN),
			Tags: []acmtypes.Tag{
				{Key: aws.String("Env"), Value: aws.String("test")},
			},
		})
		require.NoError(t, err)

		r, err := svc.ListTagsForCertificate(ctx, &acm.ListTagsForCertificateInput{
			CertificateArn: aws.String(certARN),
		})
		require.NoError(t, err)

		for _, tag := range r.Tags {
			assert.NotEqual(t, "Env", aws.ToString(tag.Key), "Tag Env should have been removed")
		}
	})

	t.Run("DeleteCertificate", func(t *testing.T) {
		if certARN == "" {
			t.Skip("No certificate ARN from RequestCertificate")
		}
		_, err := svc.DeleteCertificate(ctx, &acm.DeleteCertificateInput{
			CertificateArn: aws.String(certARN),
		})
		require.NoError(t, err)

		_, err = svc.DescribeCertificate(ctx, &acm.DescribeCertificateInput{
			CertificateArn: aws.String(certARN),
		})
		assert.Error(t, err, "DescribeCertificate should fail after deletion")
	})
}

func TestACMImportExport(t *testing.T) {
	ctx := context.Background()
	svc := testutil.ACMClient()
	certPEM, keyPEM := generateSelfSignedCert()
	var importedARN string

	t.Cleanup(func() {
		if importedARN != "" {
			svc.DeleteCertificate(ctx, &acm.DeleteCertificateInput{
				CertificateArn: aws.String(importedARN),
			})
		}
	})

	t.Run("ImportCertificate", func(t *testing.T) {
		r, err := svc.ImportCertificate(ctx, &acm.ImportCertificateInput{
			Certificate: certPEM,
			PrivateKey:  keyPEM,
		})
		require.NoError(t, err)
		importedARN = aws.ToString(r.CertificateArn)
		assert.NotEmpty(t, importedARN)
		assert.True(t, strings.HasPrefix(importedARN, "arn:aws:acm:"), "ARN should match arn:aws:acm pattern, got: %s", importedARN)
	})

	t.Run("GetImportedCertificate", func(t *testing.T) {
		if importedARN == "" {
			t.Skip("No imported certificate ARN")
		}
		r, err := svc.GetCertificate(ctx, &acm.GetCertificateInput{
			CertificateArn: aws.String(importedARN),
		})
		require.NoError(t, err)
		assert.NotEmpty(t, aws.ToString(r.Certificate))
	})

	t.Run("ExportCertificate", func(t *testing.T) {
		if importedARN == "" {
			t.Skip("No imported certificate ARN")
		}
		r, err := svc.ExportCertificate(ctx, &acm.ExportCertificateInput{
			CertificateArn: aws.String(importedARN),
			Passphrase:     []byte("test-passphrase"),
		})
		require.NoError(t, err)
		assert.NotEmpty(t, aws.ToString(r.Certificate), "Exported certificate body should not be empty")
		assert.NotEmpty(t, aws.ToString(r.PrivateKey), "Exported private key should not be empty")
	})

	t.Run("ExportRequestedCertificateFails", func(t *testing.T) {
		// Request a certificate (not imported) - export should fail
		reqResult, err := svc.RequestCertificate(ctx, &acm.RequestCertificateInput{
			DomainName: aws.String("go-export-fail.example.com"),
		})
		require.NoError(t, err)
		requestedARN := aws.ToString(reqResult.CertificateArn)

		t.Cleanup(func() {
			svc.DeleteCertificate(ctx, &acm.DeleteCertificateInput{
				CertificateArn: aws.String(requestedARN),
			})
		})

		_, err = svc.ExportCertificate(ctx, &acm.ExportCertificateInput{
			CertificateArn: aws.String(requestedARN),
			Passphrase:     []byte("test-passphrase"),
		})
		assert.Error(t, err, "ExportCertificate should fail for a requested (non-imported) certificate")
	})
}

func TestACMAccountConfiguration(t *testing.T) {
	ctx := context.Background()
	svc := testutil.ACMClient()

	_, err := svc.PutAccountConfiguration(ctx, &acm.PutAccountConfigurationInput{
		ExpiryEvents: &acmtypes.ExpiryEventsConfiguration{
			DaysBeforeExpiry: aws.Int32(45),
		},
		IdempotencyToken: aws.String("go-test-idempotency-token"),
	})
	require.NoError(t, err)

	r, err := svc.GetAccountConfiguration(ctx, &acm.GetAccountConfigurationInput{})
	require.NoError(t, err)
	require.NotNil(t, r.ExpiryEvents)
	assert.Equal(t, int32(45), aws.ToInt32(r.ExpiryEvents.DaysBeforeExpiry))
}

func TestACMErrorHandling(t *testing.T) {
	ctx := context.Background()
	svc := testutil.ACMClient()

	t.Run("DescribeNonExistent", func(t *testing.T) {
		_, err := svc.DescribeCertificate(ctx, &acm.DescribeCertificateInput{
			CertificateArn: aws.String("arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000"),
		})
		assert.Error(t, err, "DescribeCertificate should fail for non-existent ARN")
	})

	t.Run("RequestWithSANs", func(t *testing.T) {
		r, err := svc.RequestCertificate(ctx, &acm.RequestCertificateInput{
			DomainName: aws.String("go-san-test.example.com"),
			SubjectAlternativeNames: []string{
				"go-san-test.example.com",
				"www.go-san-test.example.com",
			},
		})
		require.NoError(t, err)
		sanARN := aws.ToString(r.CertificateArn)
		assert.NotEmpty(t, sanARN)

		t.Cleanup(func() {
			svc.DeleteCertificate(ctx, &acm.DeleteCertificateInput{
				CertificateArn: aws.String(sanARN),
			})
		})

		desc, err := svc.DescribeCertificate(ctx, &acm.DescribeCertificateInput{
			CertificateArn: aws.String(sanARN),
		})
		require.NoError(t, err)
		assert.Contains(t, desc.Certificate.SubjectAlternativeNames, "go-san-test.example.com")
		assert.Contains(t, desc.Certificate.SubjectAlternativeNames, "www.go-san-test.example.com")
	})

	t.Run("ImportInvalidPEM", func(t *testing.T) {
		_, err := svc.ImportCertificate(ctx, &acm.ImportCertificateInput{
			Certificate: []byte("not-valid-pem-data"),
			PrivateKey:  []byte("not-valid-key-data"),
		})
		assert.Error(t, err, "ImportCertificate should fail with invalid PEM data")
	})
}
