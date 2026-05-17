import {
  CreateDBInstanceCommand,
  DeleteDBInstanceCommand,
  DescribeDBInstancesCommand,
  ModifyDBInstanceCommand,
  RebootDBInstanceCommand,
  type CreateDBInstanceCommandInput,
  type ModifyDBInstanceCommandInput,
} from '@aws-sdk/client-rds';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { rds } from '@/lib/rdsClient';
import { rdsKeys } from '@/lib/queryKeys';

export function useDBInstances() {
  return useQuery({
    queryKey: rdsKeys.instances(),
    queryFn: async () => {
      const r = await rds.send(new DescribeDBInstancesCommand({}));
      return r.DBInstances ?? [];
    },
  });
}

export function useDBInstance(identifier: string) {
  return useQuery({
    queryKey: rdsKeys.instance(identifier),
    queryFn: async () => {
      const r = await rds.send(
        new DescribeDBInstancesCommand({
          DBInstanceIdentifier: identifier,
        }),
      );
      return r.DBInstances?.[0] ?? null;
    },
    enabled: !!identifier,
  });
}

export function useCreateDBInstance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateDBInstanceCommandInput) => {
      await rds.send(new CreateDBInstanceCommand(input));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: rdsKeys.instances() }),
  });
}

export function useDeleteDBInstance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      identifier: string;
      skipFinalSnapshot?: boolean;
    }) => {
      await rds.send(
        new DeleteDBInstanceCommand({
          DBInstanceIdentifier: input.identifier,
          SkipFinalSnapshot: input.skipFinalSnapshot ?? true,
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: rdsKeys.instances() }),
  });
}

export function useRebootDBInstance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (identifier: string) => {
      await rds.send(
        new RebootDBInstanceCommand({
          DBInstanceIdentifier: identifier,
        }),
      );
    },
    onSuccess: (_d, id) => {
      qc.invalidateQueries({ queryKey: rdsKeys.instances() });
      qc.invalidateQueries({ queryKey: rdsKeys.instance(id) });
    },
  });
}

export function useModifyDBInstance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: ModifyDBInstanceCommandInput) => {
      await rds.send(new ModifyDBInstanceCommand(input));
    },
    onSuccess: (_d, input) => {
      qc.invalidateQueries({ queryKey: rdsKeys.instances() });
      if (input.DBInstanceIdentifier) {
        qc.invalidateQueries({
          queryKey: rdsKeys.instance(input.DBInstanceIdentifier),
        });
      }
    },
  });
}
