/**
 * Cognito identity provider configuration compatibility tests.
 *
 * The response shapes asserted here were measured against the live Cognito API:
 * `IdpIdentifiers` is echoed by CreateIdentityProvider and UpdateIdentityProvider
 * only when the request supplied the member, whatever the stored value, while
 * DescribeIdentityProvider always returns it. Members a request omits are
 * preserved rather than cleared.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  CognitoIdentityProviderClient,
  CreateUserPoolCommand,
  DeleteUserPoolCommand,
  CreateIdentityProviderCommand,
  DescribeIdentityProviderCommand,
  ListIdentityProvidersCommand,
  UpdateIdentityProviderCommand,
  DeleteIdentityProviderCommand,
  IdentityProviderTypeType,
} from '@aws-sdk/client-cognito-identity-provider';
import { makeClient, uniqueName } from './setup';

const OIDC_DETAILS = {
  client_id: 'node-compat-client',
  client_secret: 'node-compat-secret',
  attributes_request_method: 'GET',
  oidc_issuer: 'https://issuer.example.com',
  authorize_scopes: 'openid',
};

describe('Cognito identity providers', () => {
  let cognito: CognitoIdentityProviderClient;
  let poolId: string;

  beforeAll(async () => {
    cognito = makeClient(CognitoIdentityProviderClient);
    const pool = await cognito.send(
      new CreateUserPoolCommand({ PoolName: uniqueName('idp-pool') })
    );
    poolId = pool.UserPool!.Id!;
  });

  afterAll(async () => {
    if (poolId) {
      await cognito.send(new DeleteUserPoolCommand({ UserPoolId: poolId }));
    }
  });

  it('defaults AttributeMapping and omits IdpIdentifiers on create', async () => {
    const created = await cognito.send(
      new CreateIdentityProviderCommand({
        UserPoolId: poolId,
        ProviderName: 'NodeOidc',
        ProviderType: IdentityProviderTypeType.OIDC,
        ProviderDetails: { ...OIDC_DETAILS },
      })
    );

    expect(created.IdentityProvider!.AttributeMapping).toEqual({ username: 'sub' });
    expect(created.IdentityProvider!.IdpIdentifiers).toBeUndefined();

    const described = await cognito.send(
      new DescribeIdentityProviderCommand({ UserPoolId: poolId, ProviderName: 'NodeOidc' })
    );
    expect(described.IdentityProvider!.IdpIdentifiers).toEqual([]);
    expect(described.IdentityProvider!.ProviderDetails!.client_id).toBe('node-compat-client');

    await cognito.send(
      new DeleteIdentityProviderCommand({ UserPoolId: poolId, ProviderName: 'NodeOidc' })
    );
  });

  it('preserves members the update request omits', async () => {
    await cognito.send(
      new CreateIdentityProviderCommand({
        UserPoolId: poolId,
        ProviderName: 'NodeOidcAliased',
        ProviderType: IdentityProviderTypeType.OIDC,
        ProviderDetails: { ...OIDC_DETAILS },
        AttributeMapping: { email: 'email', username: 'sub' },
        IdpIdentifiers: ['node-alias'],
      })
    );

    const updated = await cognito.send(
      new UpdateIdentityProviderCommand({
        UserPoolId: poolId,
        ProviderName: 'NodeOidcAliased',
        ProviderDetails: { ...OIDC_DETAILS },
      })
    );
    expect(updated.IdentityProvider!.IdpIdentifiers).toBeUndefined();

    const described = await cognito.send(
      new DescribeIdentityProviderCommand({
        UserPoolId: poolId,
        ProviderName: 'NodeOidcAliased',
      })
    );
    expect(described.IdentityProvider!.IdpIdentifiers).toEqual(['node-alias']);
    expect(described.IdentityProvider!.AttributeMapping).toEqual({
      email: 'email',
      username: 'sub',
    });

    await cognito.send(
      new DeleteIdentityProviderCommand({
        UserPoolId: poolId,
        ProviderName: 'NodeOidcAliased',
      })
    );
  });

  it('lists providers as summaries without provider details', async () => {
    await cognito.send(
      new CreateIdentityProviderCommand({
        UserPoolId: poolId,
        ProviderName: 'NodeOidcListed',
        ProviderType: IdentityProviderTypeType.OIDC,
        ProviderDetails: { ...OIDC_DETAILS },
      })
    );

    const listed = await cognito.send(new ListIdentityProvidersCommand({ UserPoolId: poolId }));

    expect(listed.Providers).toHaveLength(1);
    expect(listed.Providers![0].ProviderName).toBe('NodeOidcListed');
    expect(listed.Providers![0].ProviderType).toBe('OIDC');
    expect(listed.Providers![0].CreationDate).toBeInstanceOf(Date);
    expect(listed.Providers![0]).not.toHaveProperty('ProviderDetails');

    await cognito.send(
      new DeleteIdentityProviderCommand({ UserPoolId: poolId, ProviderName: 'NodeOidcListed' })
    );
    const empty = await cognito.send(new ListIdentityProvidersCommand({ UserPoolId: poolId }));
    expect(empty.Providers).toEqual([]);
  });

  it('rejects a duplicate provider name and an unknown provider type', async () => {
    await cognito.send(
      new CreateIdentityProviderCommand({
        UserPoolId: poolId,
        ProviderName: 'NodeOidcDuplicate',
        ProviderType: IdentityProviderTypeType.OIDC,
        ProviderDetails: { ...OIDC_DETAILS },
      })
    );

    // The SDK maps the wire `__type` onto `error.name` and puts the AWS text in
    // `message`, so the exception has to be matched on the name.
    await expect(
      cognito.send(
        new CreateIdentityProviderCommand({
          UserPoolId: poolId,
          ProviderName: 'NodeOidcDuplicate',
          ProviderType: IdentityProviderTypeType.OIDC,
          ProviderDetails: { ...OIDC_DETAILS },
        })
      )
    ).rejects.toMatchObject({ name: 'DuplicateProviderException' });

    await expect(
      cognito.send(
        new CreateIdentityProviderCommand({
          UserPoolId: poolId,
          ProviderName: 'NodeBogus',
          ProviderType: 'NotARealType' as IdentityProviderTypeType,
          ProviderDetails: { client_id: 'x' },
        })
      )
    ).rejects.toMatchObject({
      name: 'InvalidParameterException',
      message: expect.stringContaining('Member must satisfy enum value set'),
    });

    await cognito.send(
      new DeleteIdentityProviderCommand({
        UserPoolId: poolId,
        ProviderName: 'NodeOidcDuplicate',
      })
    );
  });
});
