import {
  CreateDBClusterCommand,
  DeleteDBClusterCommand,
  DescribeDBClustersCommand,
  ModifyDBClusterCommand,
  type CreateDBClusterCommandInput,
  type ModifyDBClusterCommandInput,
} from '@aws-sdk/client-rds';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { rds } from '@/lib/rdsClient';
import { rdsKeys } from '@/lib/queryKeys';

export function useDBClusters() {
  return useQuery({
    queryKey: rdsKeys.clusters(),
    queryFn: async () => {
      const r = await rds.send(new DescribeDBClustersCommand({}));
      return r.DBClusters ?? [];
    },
  });
}

export function useDBCluster(identifier: string) {
  return useQuery({
    queryKey: rdsKeys.cluster(identifier),
    queryFn: async () => {
      const r = await rds.send(
        new DescribeDBClustersCommand({
          DBClusterIdentifier: identifier,
        }),
      );
      return r.DBClusters?.[0] ?? null;
    },
    enabled: !!identifier,
  });
}

export function useCreateDBCluster() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateDBClusterCommandInput) => {
      await rds.send(new CreateDBClusterCommand(input));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: rdsKeys.clusters() }),
  });
}

export function useDeleteDBCluster() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (identifier: string) => {
      await rds.send(
        new DeleteDBClusterCommand({
          DBClusterIdentifier: identifier,
          SkipFinalSnapshot: true,
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: rdsKeys.clusters() }),
  });
}

export function useModifyDBCluster() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: ModifyDBClusterCommandInput) => {
      await rds.send(new ModifyDBClusterCommand(input));
    },
    onSuccess: (_d, input) => {
      qc.invalidateQueries({ queryKey: rdsKeys.clusters() });
      if (input.DBClusterIdentifier) {
        qc.invalidateQueries({
          queryKey: rdsKeys.cluster(input.DBClusterIdentifier),
        });
      }
    },
  });
}
