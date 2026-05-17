import {
  DeleteFunctionConcurrencyCommand,
  GetFunctionConcurrencyCommand,
  PutFunctionConcurrencyCommand,
} from '@aws-sdk/client-lambda';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { lambda } from '@/lib/lambdaClient';
import { lambdaKeys } from '@/lib/queryKeys';

export function useConcurrency(name: string) {
  return useQuery({
    queryKey: lambdaKeys.concurrency(name),
    queryFn: async (): Promise<number | null> => {
      const r = await lambda.send(
        new GetFunctionConcurrencyCommand({ FunctionName: name }),
      );
      return r.ReservedConcurrentExecutions ?? null;
    },
    enabled: !!name,
  });
}

export function usePutConcurrency(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (value: number) => {
      await lambda.send(
        new PutFunctionConcurrencyCommand({
          FunctionName: name,
          ReservedConcurrentExecutions: value,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: lambdaKeys.concurrency(name) }),
  });
}

export function useDeleteConcurrency(name: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      await lambda.send(
        new DeleteFunctionConcurrencyCommand({ FunctionName: name }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: lambdaKeys.concurrency(name) }),
  });
}
