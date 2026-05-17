import {
  DescribeInstancesCommand,
  ModifyInstanceAttributeCommand,
  RebootInstancesCommand,
  RunInstancesCommand,
  StartInstancesCommand,
  StopInstancesCommand,
  TerminateInstancesCommand,
  type Instance,
  type ModifyInstanceAttributeCommandInput,
  type RunInstancesCommandInput,
} from '@aws-sdk/client-ec2';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useInstances() {
  return useQuery({
    queryKey: ec2Keys.instances(),
    queryFn: async (): Promise<Instance[]> => {
      const all: Instance[] = [];
      let token: string | undefined;
      do {
        const r = await ec2.send(
          new DescribeInstancesCommand({ NextToken: token, MaxResults: 100 }),
        );
        for (const res of r.Reservations ?? []) {
          for (const inst of res.Instances ?? []) {
            all.push(inst);
          }
        }
        token = r.NextToken;
      } while (token);
      return all;
    },
  });
}

export function useInstance(id: string) {
  return useQuery({
    queryKey: ec2Keys.instance(id),
    queryFn: async (): Promise<Instance | null> => {
      const r = await ec2.send(
        new DescribeInstancesCommand({ InstanceIds: [id] }),
      );
      return r.Reservations?.[0]?.Instances?.[0] ?? null;
    },
    enabled: !!id,
  });
}

export function useRunInstances() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: RunInstancesCommandInput) => {
      const r = await ec2.send(new RunInstancesCommand(input));
      return r.Instances ?? [];
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.instances() }),
  });
}

export function useStartInstances() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (ids: string[]) => {
      await ec2.send(new StartInstancesCommand({ InstanceIds: ids }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ec2'] }),
  });
}

export function useStopInstances() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (ids: string[]) => {
      await ec2.send(new StopInstancesCommand({ InstanceIds: ids }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ec2'] }),
  });
}

export function useRebootInstances() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (ids: string[]) => {
      await ec2.send(new RebootInstancesCommand({ InstanceIds: ids }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ec2'] }),
  });
}

export function useTerminateInstances() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (ids: string[]) => {
      await ec2.send(new TerminateInstancesCommand({ InstanceIds: ids }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ec2'] }),
  });
}

export function useModifyInstanceAttribute() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: ModifyInstanceAttributeCommandInput) => {
      await ec2.send(new ModifyInstanceAttributeCommand(input));
    },
    onSuccess: (_d, input) => {
      qc.invalidateQueries({ queryKey: ec2Keys.instances() });
      if (input.InstanceId) {
        qc.invalidateQueries({ queryKey: ec2Keys.instance(input.InstanceId) });
      }
    },
  });
}
