package tests

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/iot"
	iottypes "github.com/aws/aws-sdk-go-v2/service/iot/types"
	"github.com/aws/aws-sdk-go-v2/service/iotdataplane"
	jobsdata "github.com/aws/aws-sdk-go-v2/service/iotjobsdataplane"
	jobsdatatypes "github.com/aws/aws-sdk-go-v2/service/iotjobsdataplane/types"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestIoT(t *testing.T) {
	ctx := context.Background()
	svc := testutil.IoTClient()

	t.Run("DescribeEndpoint", func(t *testing.T) {
		response, err := svc.DescribeEndpoint(ctx, &iot.DescribeEndpointInput{
			EndpointType: aws.String("iot:Data-ATS"),
		})
		require.NoError(t, err)
		assert.NotEmpty(t, aws.ToString(response.EndpointAddress))
	})

	t.Run("ThingRegistryCrud", func(t *testing.T) {
		thingName := "go-iot-thing"
		otherThingName := "go-iot-other-thing"
		_, _ = svc.DeleteThing(ctx, &iot.DeleteThingInput{ThingName: aws.String(thingName)})
		_, _ = svc.DeleteThing(ctx, &iot.DeleteThingInput{ThingName: aws.String(otherThingName)})

		_, err := svc.DescribeThing(ctx, &iot.DescribeThingInput{ThingName: aws.String(thingName)})
		require.Error(t, err)

		created, err := svc.CreateThing(ctx, &iot.CreateThingInput{
			ThingName: aws.String(thingName),
			AttributePayload: &iottypes.AttributePayload{
				Attributes: map[string]string{"env": "go"},
			},
		})
		require.NoError(t, err)
		assert.Equal(t, thingName, aws.ToString(created.ThingName))
		assert.True(t, strings.HasSuffix(aws.ToString(created.ThingArn), ":thing/"+thingName))

		idempotent, err := svc.CreateThing(ctx, &iot.CreateThingInput{
			ThingName: aws.String(thingName),
			AttributePayload: &iottypes.AttributePayload{
				Attributes: map[string]string{"env": "go"},
			},
		})
		require.NoError(t, err)
		assert.Equal(t, thingName, aws.ToString(idempotent.ThingName))

		_, err = svc.CreateThing(ctx, &iot.CreateThingInput{ThingName: aws.String(thingName)})
		require.Error(t, err)

		described, err := svc.DescribeThing(ctx, &iot.DescribeThingInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		assert.Equal(t, "go", described.Attributes["env"])
		_, err = svc.CreateThing(ctx, &iot.CreateThingInput{ThingName: aws.String(otherThingName)})
		require.NoError(t, err)

		listed, err := svc.ListThings(ctx, &iot.ListThingsInput{})
		require.NoError(t, err)
		found := false
		for _, thing := range listed.Things {
			if aws.ToString(thing.ThingName) == thingName {
				found = true
			}
		}
		assert.True(t, found)

		firstPage, err := svc.ListThings(ctx, &iot.ListThingsInput{MaxResults: aws.Int32(1)})
		require.NoError(t, err)
		require.Len(t, firstPage.Things, 1)
		require.NotEmpty(t, aws.ToString(firstPage.NextToken))
		secondPage, err := svc.ListThings(ctx, &iot.ListThingsInput{MaxResults: aws.Int32(1), NextToken: firstPage.NextToken})
		require.NoError(t, err)
		require.Len(t, secondPage.Things, 1)

		_, err = svc.UpdateThing(ctx, &iot.UpdateThingInput{
			ThingName: aws.String(thingName),
			AttributePayload: &iottypes.AttributePayload{
				Attributes: map[string]string{"env": "updated", "owner": "iot"},
			},
		})
		require.NoError(t, err)
		_, err = svc.UpdateThing(ctx, &iot.UpdateThingInput{
			ThingName:        aws.String(thingName),
			ExpectedVersion:  aws.Int64(2),
			AttributePayload: &iottypes.AttributePayload{Attributes: map[string]string{"env": "versioned", "owner": "iot"}},
		})
		require.NoError(t, err)
		_, err = svc.UpdateThing(ctx, &iot.UpdateThingInput{
			ThingName:        aws.String(thingName),
			ExpectedVersion:  aws.Int64(2),
			AttributePayload: &iottypes.AttributePayload{Attributes: map[string]string{"env": "stale"}},
		})
		require.Error(t, err)
		assert.Contains(t, err.Error(), "VersionConflictException")

		updated, err := svc.DescribeThing(ctx, &iot.DescribeThingInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		assert.Equal(t, "versioned", updated.Attributes["env"])
		assert.Equal(t, "iot", updated.Attributes["owner"])

		_, err = svc.DeleteThing(ctx, &iot.DeleteThingInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		_, err = svc.DeleteThing(ctx, &iot.DeleteThingInput{ThingName: aws.String(otherThingName)})
		require.NoError(t, err)

		_, err = svc.DescribeThing(ctx, &iot.DescribeThingInput{ThingName: aws.String(thingName)})
		require.Error(t, err)
	})

	t.Run("ThingTags", func(t *testing.T) {
		thingName := "go-iot-tagged-thing"
		_, _ = svc.DeleteThing(ctx, &iot.DeleteThingInput{ThingName: aws.String(thingName)})

		created, err := svc.CreateThing(ctx, &iot.CreateThingInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		thingArn := aws.ToString(created.ThingArn)

		listed, err := svc.ListTagsForResource(ctx, &iot.ListTagsForResourceInput{ResourceArn: aws.String(thingArn)})
		require.NoError(t, err)
		assert.Empty(t, listed.Tags)

		_, err = svc.TagResource(ctx, &iot.TagResourceInput{
			ResourceArn: aws.String(thingArn),
			Tags: []iottypes.Tag{
				{Key: aws.String("env"), Value: aws.String("go")},
				{Key: aws.String("owner"), Value: aws.String("iot")},
			},
		})
		require.NoError(t, err)

		listed, err = svc.ListTagsForResource(ctx, &iot.ListTagsForResourceInput{ResourceArn: aws.String(thingArn)})
		require.NoError(t, err)
		assert.Equal(t, map[string]string{"env": "go", "owner": "iot"}, tagsByKey(listed.Tags))

		_, err = svc.UntagResource(ctx, &iot.UntagResourceInput{
			ResourceArn: aws.String(thingArn),
			TagKeys:     []string{"env"},
		})
		require.NoError(t, err)

		listed, err = svc.ListTagsForResource(ctx, &iot.ListTagsForResourceInput{ResourceArn: aws.String(thingArn)})
		require.NoError(t, err)
		assert.Equal(t, map[string]string{"owner": "iot"}, tagsByKey(listed.Tags))

		_, err = svc.ListTagsForResource(ctx, &iot.ListTagsForResourceInput{
			ResourceArn: aws.String("arn:aws:iot:us-east-1:000000000000:thing/missing-tagged-thing"),
		})
		require.Error(t, err)
	})

	t.Run("CertificatesPoliciesAndAttachments", func(t *testing.T) {
		createdCert, err := svc.CreateKeysAndCertificate(ctx, &iot.CreateKeysAndCertificateInput{SetAsActive: true})
		require.NoError(t, err)
		certArn := aws.ToString(createdCert.CertificateArn)
		certID := aws.ToString(createdCert.CertificateId)
		assert.Contains(t, aws.ToString(createdCert.CertificatePem), "BEGIN CERTIFICATE")
		require.NotNil(t, createdCert.KeyPair)

		described, err := svc.DescribeCertificate(ctx, &iot.DescribeCertificateInput{CertificateId: aws.String(certID)})
		require.NoError(t, err)
		assert.Equal(t, iottypes.CertificateStatusActive, described.CertificateDescription.Status)

		listedCerts, err := svc.ListCertificates(ctx, &iot.ListCertificatesInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, listedCerts.Certificates)

		_, err = svc.UpdateCertificate(ctx, &iot.UpdateCertificateInput{CertificateId: aws.String(certID), NewStatus: iottypes.CertificateStatusInactive})
		require.NoError(t, err)

		described, err = svc.DescribeCertificate(ctx, &iot.DescribeCertificateInput{CertificateId: aws.String(certID)})
		require.NoError(t, err)
		assert.Equal(t, iottypes.CertificateStatusInactive, described.CertificateDescription.Status)

		policyName := "go-iot-policy"
		policyDocument := `{"Version":"2012-10-17","Statement":[]}`
		createdPolicy, err := svc.CreatePolicy(ctx, &iot.CreatePolicyInput{PolicyName: aws.String(policyName), PolicyDocument: aws.String(policyDocument)})
		require.NoError(t, err)
		assert.Equal(t, policyName, aws.ToString(createdPolicy.PolicyName))

		gotPolicy, err := svc.GetPolicy(ctx, &iot.GetPolicyInput{PolicyName: aws.String(policyName)})
		require.NoError(t, err)
		assert.Contains(t, aws.ToString(gotPolicy.PolicyDocument), "2012-10-17")

		listedPolicies, err := svc.ListPolicies(ctx, &iot.ListPoliciesInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, listedPolicies.Policies)

		_, err = svc.AttachPolicy(ctx, &iot.AttachPolicyInput{PolicyName: aws.String(policyName), Target: aws.String(certArn)})
		require.NoError(t, err)
		_, err = svc.DetachPolicy(ctx, &iot.DetachPolicyInput{PolicyName: aws.String(policyName), Target: aws.String(certArn)})
		require.NoError(t, err)

		thingName := "go-iot-principal-thing"
		_, _ = svc.DeleteThing(ctx, &iot.DeleteThingInput{ThingName: aws.String(thingName)})
		_, err = svc.CreateThing(ctx, &iot.CreateThingInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		_, err = svc.AttachThingPrincipal(ctx, &iot.AttachThingPrincipalInput{ThingName: aws.String(thingName), Principal: aws.String(certArn)})
		require.NoError(t, err)
		principals, err := svc.ListThingPrincipals(ctx, &iot.ListThingPrincipalsInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		assert.Contains(t, principals.Principals, certArn)
		_, err = svc.DetachThingPrincipal(ctx, &iot.DetachThingPrincipalInput{ThingName: aws.String(thingName), Principal: aws.String(certArn)})
		require.NoError(t, err)
	})

		t.Run("IoTDataShadowsAndPublish", func(t *testing.T) {
		dataSvc := testutil.IoTDataClient()
		thingName := "go-iot-shadow-thing"

		_, err := dataSvc.DeleteConnection(ctx, &iotdataplane.DeleteConnectionInput{ClientId: aws.String("go-iot-missing-client")})
		require.Error(t, err)
		assert.Contains(t, err.Error(), "ResourceNotFoundException")

		_, err = dataSvc.GetThingShadow(ctx, &iotdataplane.GetThingShadowInput{ThingName: aws.String(thingName)})
		require.Error(t, err)

		updated, err := dataSvc.UpdateThingShadow(ctx, &iotdataplane.UpdateThingShadowInput{
			ThingName: aws.String(thingName),
			Payload:   []byte(`{"state":{"desired":{"color":"blue"}}}`),
		})
		require.NoError(t, err)
		var shadow map[string]any
		require.NoError(t, json.Unmarshal(updated.Payload, &shadow))
		assert.Equal(t, float64(1), shadow["version"])

		_, err = dataSvc.UpdateThingShadow(ctx, &iotdataplane.UpdateThingShadowInput{
			ThingName: aws.String(thingName),
			Payload:   []byte(`{"state":{"reported":{"color":"green"}}}`),
		})
		require.NoError(t, err)

		got, err := dataSvc.GetThingShadow(ctx, &iotdataplane.GetThingShadowInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		require.NoError(t, json.Unmarshal(got.Payload, &shadow))
		state := shadow["state"].(map[string]any)
		assert.Equal(t, "blue", state["desired"].(map[string]any)["color"])
		assert.Equal(t, "green", state["reported"].(map[string]any)["color"])

		_, err = dataSvc.UpdateThingShadow(ctx, &iotdataplane.UpdateThingShadowInput{
			ThingName:  aws.String(thingName),
			ShadowName: aws.String("settings"),
			Payload:    []byte(`{"state":{"desired":{"mode":"auto"}}}`),
		})
		require.NoError(t, err)
		named, err := dataSvc.ListNamedShadowsForThing(ctx, &iotdataplane.ListNamedShadowsForThingInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		assert.Contains(t, named.Results, "settings")

		_, err = dataSvc.Publish(ctx, &iotdataplane.PublishInput{Topic: aws.String("devices/" + thingName + "/events"), Payload: []byte("payload")})
		require.NoError(t, err)
		_, err = dataSvc.DeleteThingShadow(ctx, &iotdataplane.DeleteThingShadowInput{ThingName: aws.String(thingName), ShadowName: aws.String("settings")})
		require.NoError(t, err)
		_, err = dataSvc.DeleteThingShadow(ctx, &iotdataplane.DeleteThingShadowInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
	})

	t.Run("TopicRuleCrudAndSqsAction", func(t *testing.T) {
		dataSvc := testutil.IoTDataClient()
		sqsSvc := testutil.SQSClient()
		ruleName := "go-iot-topic-rule"
		queueName := "go-iot-rule-queue"
		queue, err := sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(queueName)})
		require.NoError(t, err)
		queueURL := aws.ToString(queue.QueueUrl)
		defer sqsSvc.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: aws.String(queueURL)})
		defer svc.DeleteTopicRule(ctx, &iot.DeleteTopicRuleInput{RuleName: aws.String(ruleName)})

		_, err = svc.CreateTopicRule(ctx, &iot.CreateTopicRuleInput{
			RuleName: aws.String(ruleName),
			TopicRulePayload: &iottypes.TopicRulePayload{
				Sql:          aws.String("SELECT * FROM 'devices/go-iot/rules'"),
				Description:  aws.String("go topic rule"),
				RuleDisabled: aws.Bool(false),
				Actions: []iottypes.Action{{Sqs: &iottypes.SqsAction{
					RoleArn:   aws.String("arn:aws:iam::000000000000:role/iot-rule-role"),
					QueueUrl:  aws.String(queueURL),
					UseBase64: aws.Bool(false),
				}}},
			},
		})
		require.NoError(t, err)

		got, err := svc.GetTopicRule(ctx, &iot.GetTopicRuleInput{RuleName: aws.String(ruleName)})
		require.NoError(t, err)
		require.NotNil(t, got.Rule)
		assert.Equal(t, ruleName, aws.ToString(got.Rule.RuleName))
		assert.Equal(t, queueURL, aws.ToString(got.Rule.Actions[0].Sqs.QueueUrl))

		_, err = svc.DisableTopicRule(ctx, &iot.DisableTopicRuleInput{RuleName: aws.String(ruleName)})
		require.NoError(t, err)
		listed, err := svc.ListTopicRules(ctx, &iot.ListTopicRulesInput{})
		require.NoError(t, err)
		foundDisabled := false
		for _, rule := range listed.Rules {
			if aws.ToString(rule.RuleName) == ruleName && aws.ToBool(rule.RuleDisabled) {
				foundDisabled = true
			}
		}
		assert.True(t, foundDisabled)

		_, err = svc.EnableTopicRule(ctx, &iot.EnableTopicRuleInput{RuleName: aws.String(ruleName)})
		require.NoError(t, err)
		_, err = dataSvc.Publish(ctx, &iotdataplane.PublishInput{Topic: aws.String("devices/go-iot/rules"), Payload: []byte("go-rule-payload")})
		require.NoError(t, err)

		received, err := sqsSvc.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{QueueUrl: aws.String(queueURL), MaxNumberOfMessages: 1})
		require.NoError(t, err)
		require.Len(t, received.Messages, 1)
		assert.Equal(t, "go-rule-payload", aws.ToString(received.Messages[0].Body))
	})

	t.Run("ThingTypesGroupsAndJobs", func(t *testing.T) {
		jobsSvc := testutil.IoTJobsDataClient()
		thingType := "go-iot-type"
		thingName := "go-iot-typed-thing"
		groupName := "go-iot-group"
		jobID := "go-iot-job"
		_, _ = svc.DeleteThing(ctx, &iot.DeleteThingInput{ThingName: aws.String(thingName)})
		_, _ = svc.DeleteThingGroup(ctx, &iot.DeleteThingGroupInput{ThingGroupName: aws.String(groupName)})
		_, _ = svc.DeprecateThingType(ctx, &iot.DeprecateThingTypeInput{ThingTypeName: aws.String(thingType)})
		_, _ = svc.DeleteThingType(ctx, &iot.DeleteThingTypeInput{ThingTypeName: aws.String(thingType)})

		jobsEndpoint, err := svc.DescribeEndpoint(ctx, &iot.DescribeEndpointInput{EndpointType: aws.String("iot:Jobs")})
		require.NoError(t, err)
		assert.NotEmpty(t, aws.ToString(jobsEndpoint.EndpointAddress))

		createdType, err := svc.CreateThingType(ctx, &iot.CreateThingTypeInput{
			ThingTypeName: aws.String(thingType),
			ThingTypeProperties: &iottypes.ThingTypeProperties{
				ThingTypeDescription: aws.String("go type"),
				SearchableAttributes:  []string{"model"},
			},
		})
		require.NoError(t, err)
		assert.Equal(t, thingType, aws.ToString(createdType.ThingTypeName))
		describedType, err := svc.DescribeThingType(ctx, &iot.DescribeThingTypeInput{ThingTypeName: aws.String(thingType)})
		require.NoError(t, err)
		assert.Equal(t, "go type", aws.ToString(describedType.ThingTypeProperties.ThingTypeDescription))

		_, err = svc.UpdateThingType(ctx, &iot.UpdateThingTypeInput{
			ThingTypeName: aws.String(thingType),
			ThingTypeProperties: &iottypes.ThingTypeProperties{
				ThingTypeDescription: aws.String("go type updated"),
				SearchableAttributes:  []string{"model", "fw"},
			},
		})
		require.NoError(t, err)

		createdThing, err := svc.CreateThing(ctx, &iot.CreateThingInput{
			ThingName:     aws.String(thingName),
			ThingTypeName: aws.String(thingType),
			AttributePayload: &iottypes.AttributePayload{
				Attributes: map[string]string{"model": "g1"},
			},
		})
		require.NoError(t, err)
		thingArn := aws.ToString(createdThing.ThingArn)
		describedThing, err := svc.DescribeThing(ctx, &iot.DescribeThingInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		assert.Equal(t, thingType, aws.ToString(describedThing.ThingTypeName))

		createdGroup, err := svc.CreateThingGroup(ctx, &iot.CreateThingGroupInput{
			ThingGroupName: aws.String(groupName),
			ThingGroupProperties: &iottypes.ThingGroupProperties{
				ThingGroupDescription: aws.String("go group"),
				AttributePayload:      &iottypes.AttributePayload{Attributes: map[string]string{"fleet": "go"}},
			},
		})
		require.NoError(t, err)
		assert.Equal(t, groupName, aws.ToString(createdGroup.ThingGroupName))
		_, err = svc.AddThingToThingGroup(ctx, &iot.AddThingToThingGroupInput{ThingGroupName: aws.String(groupName), ThingName: aws.String(thingName)})
		require.NoError(t, err)
		thingsInGroup, err := svc.ListThingsInThingGroup(ctx, &iot.ListThingsInThingGroupInput{ThingGroupName: aws.String(groupName)})
		require.NoError(t, err)
		assert.Contains(t, thingsInGroup.Things, thingName)

		jobDoc := `{"operation":"reboot"}`
		createdJob, err := svc.CreateJob(ctx, &iot.CreateJobInput{JobId: aws.String(jobID), Targets: []string{thingArn}, Document: aws.String(jobDoc), Description: aws.String("go job")})
		require.NoError(t, err)
		assert.Equal(t, jobID, aws.ToString(createdJob.JobId))
		pending, err := jobsSvc.GetPendingJobExecutions(ctx, &jobsdata.GetPendingJobExecutionsInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		require.NotEmpty(t, pending.QueuedJobs)
		started, err := jobsSvc.StartNextPendingJobExecution(ctx, &jobsdata.StartNextPendingJobExecutionInput{ThingName: aws.String(thingName), StatusDetails: map[string]string{"phase": "download"}})
		require.NoError(t, err)
		assert.Equal(t, jobsdatatypes.JobExecutionStatusInProgress, started.Execution.Status)
		updated, err := jobsSvc.UpdateJobExecution(ctx, &jobsdata.UpdateJobExecutionInput{
			ThingName:                aws.String(thingName),
			JobId:                    aws.String(jobID),
			Status:                   jobsdatatypes.JobExecutionStatusSucceeded,
			ExpectedVersion:          aws.Int64(2),
			IncludeJobExecutionState: aws.Bool(true),
			IncludeJobDocument:       aws.Bool(true),
		})
		require.NoError(t, err)
		assert.Equal(t, jobsdatatypes.JobExecutionStatusSucceeded, updated.ExecutionState.Status)
		assert.Contains(t, aws.ToString(updated.JobDocument), "reboot")

		_, err = svc.RemoveThingFromThingGroup(ctx, &iot.RemoveThingFromThingGroupInput{ThingGroupName: aws.String(groupName), ThingName: aws.String(thingName)})
		require.NoError(t, err)
		_, err = svc.DeleteThingGroup(ctx, &iot.DeleteThingGroupInput{ThingGroupName: aws.String(groupName)})
		require.NoError(t, err)
		_, err = svc.DeleteThing(ctx, &iot.DeleteThingInput{ThingName: aws.String(thingName)})
		require.NoError(t, err)
		_, err = svc.DeprecateThingType(ctx, &iot.DeprecateThingTypeInput{ThingTypeName: aws.String(thingType)})
		require.NoError(t, err)
		_, err = svc.DeleteThingType(ctx, &iot.DeleteThingTypeInput{ThingTypeName: aws.String(thingType)})
		require.NoError(t, err)
	})

	t.Run("MqttConnectPublishSubscribe", func(t *testing.T) {
		topic := "devices/go-iot-mqtt/events"
		payload := []byte("go-mqtt")

		subscriber := mqttConnect(t, "go-iot-mqtt-sub")
		defer subscriber.Close()
		mqttSubscribe(t, subscriber, topic)

		publisher := mqttConnect(t, "go-iot-mqtt-pub")
		mqttPublish(t, publisher, topic, payload)
		publisher.Close()

		receivedTopic, receivedPayload := mqttReadPublish(t, subscriber)
		assert.Equal(t, topic, receivedTopic)
		assert.Equal(t, payload, receivedPayload)
	})

	t.Run("Mqtt5Connect", func(t *testing.T) {
		client := mqtt5Connect(t, "go-iot-mqtt5")
		client.Close()
	})

	t.Run("MqttShadowReservedTopics", func(t *testing.T) {
		thingName := "go-iot-shadow"
		subscriber := mqttConnect(t, "go-iot-shadow-sub")
		defer subscriber.Close()
		mqttSubscribe(t, subscriber, "$aws/things/"+thingName+"/shadow/update/accepted")
		mqttSubscribe(t, subscriber, "$aws/things/"+thingName+"/shadow/get/accepted")
		mqttSubscribe(t, subscriber, "$aws/things/"+thingName+"/shadow/delete/accepted")

		publisher := mqttConnect(t, "go-iot-shadow-pub")
		defer publisher.Close()
		mqttPublish(t, publisher, "$aws/things/"+thingName+"/shadow/update", []byte(`{"state":{"desired":{"color":"blue"}},"clientToken":"update-token"}`))
		topic, payload := mqttReadPublish(t, subscriber)
		assert.Equal(t, "$aws/things/"+thingName+"/shadow/update/accepted", topic)
		var accepted map[string]any
		require.NoError(t, json.Unmarshal(payload, &accepted))
		assert.Equal(t, "update-token", accepted["clientToken"])

		mqttPublish(t, publisher, "$aws/things/"+thingName+"/shadow/get", []byte(`{"clientToken":"get-token"}`))
		topic, payload = mqttReadPublish(t, subscriber)
		assert.Equal(t, "$aws/things/"+thingName+"/shadow/get/accepted", topic)
		var got map[string]any
		require.NoError(t, json.Unmarshal(payload, &got))
		assert.Equal(t, "get-token", got["clientToken"])

		mqttPublish(t, publisher, "$aws/things/"+thingName+"/shadow/delete", []byte(`{"clientToken":"delete-token"}`))
		topic, payload = mqttReadPublish(t, subscriber)
		assert.Equal(t, "$aws/things/"+thingName+"/shadow/delete/accepted", topic)
		var deleted map[string]any
		require.NoError(t, json.Unmarshal(payload, &deleted))
		assert.Equal(t, "delete-token", deleted["clientToken"])
	})
}

func tagsByKey(tags []iottypes.Tag) map[string]string {
	result := map[string]string{}
	for _, tag := range tags {
		result[aws.ToString(tag.Key)] = aws.ToString(tag.Value)
	}
	return result
}

func mqttConnect(t *testing.T, clientID string) net.Conn {
	t.Helper()
	conn, err := net.DialTimeout("tcp", "floci:1883", 5*time.Second)
	require.NoError(t, err)
	require.NoError(t, conn.SetDeadline(time.Now().Add(5*time.Second)))
	body := append(mqttUTF8("MQTT"), []byte{4, 2, 0, 60}...)
	body = append(body, mqttUTF8(clientID)...)
	_, err = conn.Write(append([]byte{0x10}, append(mqttRemainingLength(len(body)), body...)...))
	require.NoError(t, err)
	packet := mqttReadPacket(t, conn)
	require.Equal(t, []byte{0x20, 0x02, 0x00, 0x00}, packet)
	return conn
}

func mqtt5Connect(t *testing.T, clientID string) net.Conn {
	t.Helper()
	conn, err := net.DialTimeout("tcp", "floci:1883", 5*time.Second)
	require.NoError(t, err)
	require.NoError(t, conn.SetDeadline(time.Now().Add(5*time.Second)))
	body := append(mqttUTF8("MQTT"), []byte{5, 2, 0, 60, 0}...)
	body = append(body, mqttUTF8(clientID)...)
	_, err = conn.Write(append([]byte{0x10}, append(mqttRemainingLength(len(body)), body...)...))
	require.NoError(t, err)
	packet := mqttReadPacket(t, conn)
	mqttAssertV5Connack(t, packet)
	return conn
}

func mqttAssertV5Connack(t *testing.T, packet []byte) {
	t.Helper()
	require.Equal(t, byte(0x20), packet[0])
	index := 1
	for packet[index]&0x80 != 0 {
		index++
	}
	index++
	require.Equal(t, byte(0x00), packet[index])
	require.Equal(t, byte(0x00), packet[index+1])
}

func mqttSubscribe(t *testing.T, conn net.Conn, topic string) {
	t.Helper()
	body := append([]byte{0, 1}, mqttUTF8(topic)...)
	body = append(body, 0)
	_, err := conn.Write(append([]byte{0x82}, append(mqttRemainingLength(len(body)), body...)...))
	require.NoError(t, err)
	require.Equal(t, []byte{0x90, 0x03, 0x00, 0x01, 0x00}, mqttReadPacket(t, conn))
}

func mqttPublish(t *testing.T, conn net.Conn, topic string, payload []byte) {
	t.Helper()
	body := append(mqttUTF8(topic), payload...)
	_, err := conn.Write(append([]byte{0x30}, append(mqttRemainingLength(len(body)), body...)...))
	require.NoError(t, err)
}

func mqttReadPublish(t *testing.T, conn net.Conn) (string, []byte) {
	t.Helper()
	packet := mqttReadPacket(t, conn)
	require.Equal(t, byte(0x30), packet[0]&0xf0)
	index := 1
	for packet[index]&0x80 != 0 {
		index++
	}
	index++
	topicLength := int(packet[index])<<8 | int(packet[index+1])
	index += 2
	topic := string(packet[index : index+topicLength])
	index += topicLength
	return topic, packet[index:]
}

func mqttReadPacket(t *testing.T, conn net.Conn) []byte {
	t.Helper()
	fixed := make([]byte, 1)
	_, err := io.ReadFull(conn, fixed)
	require.NoError(t, err)
	packet := append([]byte{}, fixed...)
	remaining := 0
	multiplier := 1
	for {
		encoded := make([]byte, 1)
		_, err = io.ReadFull(conn, encoded)
		require.NoError(t, err)
		packet = append(packet, encoded[0])
		remaining += int(encoded[0]&127) * multiplier
		multiplier *= 128
		if encoded[0]&128 == 0 {
			break
		}
	}
	body := make([]byte, remaining)
	_, err = io.ReadFull(conn, body)
	require.NoError(t, err)
	return append(packet, body...)
}

func mqttUTF8(value string) []byte {
	bytes := []byte(value)
	return append([]byte{byte(len(bytes) >> 8), byte(len(bytes))}, bytes...)
}

func mqttRemainingLength(length int) []byte {
	var encoded []byte
	value := length
	for {
		digit := byte(value % 128)
		value /= 128
		if value > 0 {
			digit |= 128
		}
		encoded = append(encoded, digit)
		if value == 0 {
			return encoded
		}
	}
}
