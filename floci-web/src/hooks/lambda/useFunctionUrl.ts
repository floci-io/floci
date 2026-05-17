import {
  CreateFunctionUrlConfigCommand,
  DeleteFunctionUrlConfigCommand,
  FunctionUrlAuthType,
  GetFunctionUrlConfigCommand,
  UpdateFunctionUrlConfigCommand,
  type Cors,
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

export interface FunctionUrlConfig {
  url: string;
  authType: FunctionUrlAuthType;
  cors?: Cors;
}

export function useFunctionUrl(name: string) {
  return useQuery({
    queryKey: lambdaKeys.url(name),
    queryFn: async (): Promise<FunctionUrlConfig | null> => {
      try {
        const r = await lambda.send(
          new GetFunctionUrlConfigCommand({ FunctionName: name }),
        );
        if (!r.FunctionUrl) return null;
        return {
          url: r.FunctionUrl,
          authType: r.AuthType ?? FunctionUrlAuthType.NONE,
          cors: r.Cors,
        };
      } catch (e) {
        if (isMissing(e)) return null;
        throw e;
      }
    },
    enabled: !!name,
  });
}

export function useCreateFunctionUrl(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      authType: FunctionUrlAuthType;
      cors?: Cors;
    }) => {
      await lambda.send(
        new CreateFunctionUrlConfigCommand({
          FunctionName: name,
          AuthType: input.authType,
          Cors: input.cors,
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: lambdaKeys.url(name) }),
  });
}

export function useUpdateFunctionUrl(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      authType?: FunctionUrlAuthType;
      cors?: Cors;
    }) => {
      await lambda.send(
        new UpdateFunctionUrlConfigCommand({
          FunctionName: name,
          AuthType: input.authType,
          Cors: input.cors,
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: lambdaKeys.url(name) }),
  });
}

export function useDeleteFunctionUrl(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      await lambda.send(
        new DeleteFunctionUrlConfigCommand({ FunctionName: name }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: lambdaKeys.url(name) }),
  });
}
