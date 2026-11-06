// The CDK app that synthesized http-api-domain.template.json and rest-api-multi-level-domain.template.json,
// which ApiGatewayV2DomainCfnIntegrationTest deploys verbatim. To regenerate, in an empty directory:
//   npm install aws-cdk-lib@2.272.0 constructs@10 && CDK_OUTDIR=out node cdk-app.js
// then copy out/HttpApiDomain.template.json and out/RestApiMultiLevelDomain.template.json here.
// aws-cdk-lib 2.270.0 synthesizes the same resources.
const cdk = require('aws-cdk-lib');
const apigateway = require('aws-cdk-lib/aws-apigateway');
const apigwv2 = require('aws-cdk-lib/aws-apigatewayv2');
const integrations = require('aws-cdk-lib/aws-apigatewayv2-integrations');
const acm = require('aws-cdk-lib/aws-certificatemanager');
const route53 = require('aws-cdk-lib/aws-route53');
const targets = require('aws-cdk-lib/aws-route53-targets');

const app = new cdk.App();
const synthesizer = () => new cdk.DefaultStackSynthesizer({ generateBootstrapVersionRule: false });

// An HTTP API on a custom domain: the default stage mapped at the root, a second stage mapped
// under a key, and an alias record to the domain.
{
  const stack = new cdk.Stack(app, 'HttpApiDomain', { synthesizer: synthesizer() });
  const backendUrl = new cdk.CfnParameter(stack, 'BackendUrl', { type: 'String' });
  const zoneName = 'apigwv2-domain-cfn-it.example.com';
  const domainName = `cdk.${zoneName}`;
  const zone = new route53.PublicHostedZone(stack, 'Zone', { zoneName });
  const certificate = new acm.Certificate(stack, 'Certificate', {
    domainName,
    validation: acm.CertificateValidation.fromDns(zone),
  });
  const domain = new apigwv2.DomainName(stack, 'Domain', { domainName, certificate });
  const api = new apigwv2.HttpApi(stack, 'HttpApi', {
    defaultIntegration: new integrations.HttpUrlIntegration('Backend', backendUrl.valueAsString),
    defaultDomainMapping: { domainName: domain },
  });
  const prod = api.addStage('Prod', { stageName: 'prod', autoDeploy: true });
  new apigwv2.ApiMapping(stack, 'ProdMapping', { api, domainName: domain, stage: prod, apiMappingKey: 'prod' });
  new route53.ARecord(stack, 'Alias', {
    zone,
    recordName: domainName,
    target: route53.RecordTarget.fromAlias(
      new targets.ApiGatewayv2DomainProperties(domain.regionalDomainName, domain.regionalHostedZoneId)),
  });
  new cdk.CfnOutput(stack, 'ApiId', { value: api.apiId });
  new cdk.CfnOutput(stack, 'ZoneId', { value: zone.hostedZoneId });
  new cdk.CfnOutput(stack, 'RegionalDomainName', { value: domain.regionalDomainName });
  new cdk.CfnOutput(stack, 'RegionalHostedZoneId', { value: domain.regionalHostedZoneId });
}

// A REST API on a custom domain under a key with several levels, which CDK maps with
// AWS::ApiGatewayV2::ApiMapping because AWS::ApiGateway::BasePathMapping takes one level only.
{
  const stack = new cdk.Stack(app, 'RestApiMultiLevelDomain', { synthesizer: synthesizer() });
  const backendUrl = new cdk.CfnParameter(stack, 'BackendUrl', { type: 'String' });
  const zoneName = 'apigwv2-rest-cfn-it.example.com';
  const domainName = `rest.${zoneName}`;
  const zone = new route53.PublicHostedZone(stack, 'Zone', { zoneName });
  const certificate = new acm.Certificate(stack, 'Certificate', {
    domainName,
    validation: acm.CertificateValidation.fromDns(zone),
  });
  const api = new apigateway.RestApi(stack, 'RestApi');
  api.root.addResource('items').addMethod('GET', new apigateway.HttpIntegration(backendUrl.valueAsString));
  const domain = new apigateway.DomainName(stack, 'Domain', {
    domainName,
    certificate,
    mapping: api,
    basePath: 'orders/v1',
  });
  new route53.ARecord(stack, 'Alias', {
    zone,
    recordName: domainName,
    target: route53.RecordTarget.fromAlias(new targets.ApiGatewayDomain(domain)),
  });
  new cdk.CfnOutput(stack, 'RestApiId', { value: api.restApiId });
}

app.synth();
