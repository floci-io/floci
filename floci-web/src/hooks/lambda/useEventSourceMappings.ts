import {
  CreateEventSourceMappingCommand,
  DeleteEventSourceMappingCommand,
  ListEventSourceMappingsCommand,
  UpdateEventSourceMappingCommand,
  type CreateEventSourceMappingCommandInput,
  type UpdateEventSourceMappingCommandInput,
} from '@aws-sdk/client-lambda';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { lambda } from '@/lib/lambdaClient';
import { lambdaKeys } from '@/lib/queryKeys';

export function useEventSourceMappings(functionName?: string) {
  return useQuery({
    queryKey: lambdaKeys.esm(functionName),
    queryFn: async () => {
      const r = await lambda.send(
        new ListEventSourceMappingsCommand({
          FunctionName: functionName,
        }),
      );
      return r.EventSourceMappings ?? [];
    },
  });
}

export function useCreateEventSourceMapping() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateEventSourceMappingCommandInput) => {
      await lambda.send(new CreateEventSourceMappingCommand(input));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['lambda', 'event-source-mappings'] }),
  });
}

export function useUpdateEventSourceMapping() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: UpdateEventSourceMappingCommandInput) => {
      await lambda.send(new UpdateEventSourceMappingCommand(input));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['lambda', 'event-source-mappings'] }),
  });
}

export function useDeleteEventSourceMapping() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (uuid: string) => {
      await lambda.send(new DeleteEventSourceMappingCommand({ UUID: uuid }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['lambda', 'event-source-mappings'] }),
  });
}
