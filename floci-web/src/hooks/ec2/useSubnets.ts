import {
  CreateSubnetCommand,
  DeleteSubnetCommand,
  DescribeSubnetsCommand,
} from '@aws-sdk/client-ec2';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useSubnets() {
  return useQuery({
    queryKey: ec2Keys.subnets(),
    queryFn: async () => {
      const r = await ec2.send(new DescribeSubnetsCommand({}));
      return r.Subnets ?? [];
    },
  });
}

export function useCreateSubnet() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      vpcId: string;
      cidr: string;
      availabilityZone?: string;
    }) => {
      await ec2.send(
        new CreateSubnetCommand({
          VpcId: input.vpcId,
          CidrBlock: input.cidr,
          AvailabilityZone: input.availabilityZone,
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.subnets() }),
  });
}

export function useDeleteSubnet() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await ec2.send(new DeleteSubnetCommand({ SubnetId: id }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.subnets() }),
  });
}
