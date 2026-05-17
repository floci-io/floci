import {
  AllocateAddressCommand,
  AssociateAddressCommand,
  DescribeAddressesCommand,
  DisassociateAddressCommand,
  ReleaseAddressCommand,
} from '@aws-sdk/client-ec2';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useElasticIps() {
  return useQuery({
    queryKey: ec2Keys.elasticIps(),
    queryFn: async () => {
      const r = await ec2.send(new DescribeAddressesCommand({}));
      return r.Addresses ?? [];
    },
  });
}

export function useAllocateAddress() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const r = await ec2.send(new AllocateAddressCommand({ Domain: 'vpc' }));
      return r;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.elasticIps() }),
  });
}

export function useAssociateAddress() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { allocationId: string; instanceId: string }) => {
      await ec2.send(
        new AssociateAddressCommand({
          AllocationId: input.allocationId,
          InstanceId: input.instanceId,
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.elasticIps() }),
  });
}

export function useDisassociateAddress() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (associationId: string) => {
      await ec2.send(
        new DisassociateAddressCommand({ AssociationId: associationId }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.elasticIps() }),
  });
}

export function useReleaseAddress() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (allocationId: string) => {
      await ec2.send(
        new ReleaseAddressCommand({ AllocationId: allocationId }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.elasticIps() }),
  });
}
