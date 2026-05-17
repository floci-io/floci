import {
  AddPermissionCommand,
  GetPolicyCommand,
  RemovePermissionCommand,
} from '@aws-sdk/client-lambda';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { lambda } from '@/lib/lambdaClient';
import { lambdaKeys } from '@/lib/queryKeys';

function isMissing(e: unknown): boolean {
  const err = e as { name?: string; Code?: string };
  return (
    err?.name === 'ResourceNotFoundException' ||
    err?.Code === 'ResourceNotFoundException'
  );
}

export function usePolicy(name: string) {
  return useQuery({
    queryKey: lambdaKeys.policy(name),
    queryFn: async (): Promise<string | null> => {
      try {
        const r = await lambda.send(
          new GetPolicyCommand({ FunctionName: name }),
        );
        return r.Policy ?? null;
      } catch (e) {
        if (isMissing(e)) return null;
        throw e;
      }
    },
    enabled: !!name,
  });
}

export function useAddPermission(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      statementId: string;
      action: string;
      principal: string;
      sourceArn?: string;
      sourceAccount?: string;
    }) => {
      await lambda.send(
        new AddPermissionCommand({
          FunctionName: name,
          StatementId: input.statementId,
          Action: input.action,
          Principal: input.principal,
          SourceArn: input.sourceArn,
          SourceAccount: input.sourceAccount,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: lambdaKeys.policy(name) }),
  });
}

export function useRemovePermission(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (statementId: string) => {
      await lambda.send(
        new RemovePermissionCommand({
          FunctionName: name,
          StatementId: statementId,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: lambdaKeys.policy(name) }),
  });
}
