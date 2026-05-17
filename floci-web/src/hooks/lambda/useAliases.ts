import {
  CreateAliasCommand,
  DeleteAliasCommand,
  ListAliasesCommand,
  UpdateAliasCommand,
  type AliasRoutingConfiguration,
} from '@aws-sdk/client-lambda';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { lambda } from '@/lib/lambdaClient';
import { lambdaKeys } from '@/lib/queryKeys';

export function useAliases(name: string) {
  return useQuery({
    queryKey: lambdaKeys.aliases(name),
    queryFn: async () => {
      const r = await lambda.send(
        new ListAliasesCommand({ FunctionName: name }),
      );
      return r.Aliases ?? [];
    },
    enabled: !!name,
  });
}

export function useCreateAlias(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      aliasName: string;
      functionVersion: string;
      description?: string;
      routingConfig?: AliasRoutingConfiguration;
    }) => {
      await lambda.send(
        new CreateAliasCommand({
          FunctionName: name,
          Name: input.aliasName,
          FunctionVersion: input.functionVersion,
          Description: input.description,
          RoutingConfig: input.routingConfig,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: lambdaKeys.aliases(name) }),
  });
}

export function useUpdateAlias(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      aliasName: string;
      functionVersion?: string;
      description?: string;
      routingConfig?: AliasRoutingConfiguration;
    }) => {
      await lambda.send(
        new UpdateAliasCommand({
          FunctionName: name,
          Name: input.aliasName,
          FunctionVersion: input.functionVersion,
          Description: input.description,
          RoutingConfig: input.routingConfig,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: lambdaKeys.aliases(name) }),
  });
}

export function useDeleteAlias(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (aliasName: string) => {
      await lambda.send(
        new DeleteAliasCommand({ FunctionName: name, Name: aliasName }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: lambdaKeys.aliases(name) }),
  });
}
