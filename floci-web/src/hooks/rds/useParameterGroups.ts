import {
  CreateDBParameterGroupCommand,
  DeleteDBParameterGroupCommand,
  DescribeDBParameterGroupsCommand,
  DescribeDBParametersCommand,
  ModifyDBParameterGroupCommand,
  type Parameter,
} from '@aws-sdk/client-rds';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { rds } from '@/lib/rdsClient';
import { rdsKeys } from '@/lib/queryKeys';

export function useParameterGroups() {
  return useQuery({
    queryKey: rdsKeys.parameterGroups(),
    queryFn: async () => {
      const r = await rds.send(new DescribeDBParameterGroupsCommand({}));
      return r.DBParameterGroups ?? [];
    },
  });
}

export function useCreateParameterGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      name: string;
      family: string;
      description: string;
    }) => {
      await rds.send(
        new CreateDBParameterGroupCommand({
          DBParameterGroupName: input.name,
          DBParameterGroupFamily: input.family,
          Description: input.description,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: rdsKeys.parameterGroups() }),
  });
}

export function useDeleteParameterGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (name: string) => {
      await rds.send(
        new DeleteDBParameterGroupCommand({
          DBParameterGroupName: name,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: rdsKeys.parameterGroups() }),
  });
}

export function useParameters(groupName: string) {
  return useQuery({
    queryKey: rdsKeys.parameterGroupParameters(groupName),
    queryFn: async () => {
      const all: Parameter[] = [];
      let marker: string | undefined;
      do {
        const r = await rds.send(
          new DescribeDBParametersCommand({
            DBParameterGroupName: groupName,
            Marker: marker,
          }),
        );
        all.push(...(r.Parameters ?? []));
        marker = r.Marker;
      } while (marker);
      return all;
    },
    enabled: !!groupName,
  });
}

export function useModifyParameters(groupName: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (parameters: Parameter[]) => {
      await rds.send(
        new ModifyDBParameterGroupCommand({
          DBParameterGroupName: groupName,
          Parameters: parameters,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({
        queryKey: rdsKeys.parameterGroupParameters(groupName),
      }),
  });
}
