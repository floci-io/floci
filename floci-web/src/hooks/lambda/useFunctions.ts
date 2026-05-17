import {
  CreateFunctionCommand,
  DeleteFunctionCommand,
  GetFunctionCommand,
  ListFunctionsCommand,
  UpdateFunctionCodeCommand,
  UpdateFunctionConfigurationCommand,
  type CreateFunctionCommandInput,
  type UpdateFunctionCodeCommandInput,
  type UpdateFunctionConfigurationCommandInput,
} from '@aws-sdk/client-lambda';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { lambda } from '@/lib/lambdaClient';
import { lambdaKeys } from '@/lib/queryKeys';

export function useFunctions() {
  return useQuery({
    queryKey: lambdaKeys.functions(),
    queryFn: async () => {
      const r = await lambda.send(new ListFunctionsCommand({}));
      return r.Functions ?? [];
    },
  });
}

export function useFunction(name: string) {
  return useQuery({
    queryKey: lambdaKeys.function(name),
    queryFn: async () => {
      const r = await lambda.send(
        new GetFunctionCommand({ FunctionName: name }),
      );
      return r;
    },
    enabled: !!name,
  });
}

export function useCreateFunction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateFunctionCommandInput) => {
      await lambda.send(new CreateFunctionCommand(input));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: lambdaKeys.functions() }),
  });
}

export function useDeleteFunction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (name: string) => {
      await lambda.send(new DeleteFunctionCommand({ FunctionName: name }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: lambdaKeys.functions() }),
  });
}

export function useUpdateFunctionCode() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: UpdateFunctionCodeCommandInput) => {
      await lambda.send(new UpdateFunctionCodeCommand(input));
    },
    onSuccess: (_d, input) => {
      qc.invalidateQueries({ queryKey: lambdaKeys.functions() });
      if (input.FunctionName) {
        qc.invalidateQueries({
          queryKey: lambdaKeys.function(input.FunctionName),
        });
      }
    },
  });
}

export function useUpdateFunctionConfiguration() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: UpdateFunctionConfigurationCommandInput) => {
      await lambda.send(new UpdateFunctionConfigurationCommand(input));
    },
    onSuccess: (_d, input) => {
      qc.invalidateQueries({ queryKey: lambdaKeys.functions() });
      if (input.FunctionName) {
        qc.invalidateQueries({
          queryKey: lambdaKeys.function(input.FunctionName),
        });
      }
    },
  });
}
