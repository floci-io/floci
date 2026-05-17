import {
  CreateVpcCommand,
  DeleteVpcCommand,
  DescribeVpcsCommand,
} from '@aws-sdk/client-ec2';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useVpcs() {
  return useQuery({
    queryKey: ec2Keys.vpcs(),
    queryFn: async () => {
      const r = await ec2.send(new DescribeVpcsCommand({}));
      return r.Vpcs ?? [];
    },
  });
}

export function useCreateVpc() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (cidr: string) => {
      await ec2.send(new CreateVpcCommand({ CidrBlock: cidr }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.vpcs() }),
  });
}

export function useDeleteVpc() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await ec2.send(new DeleteVpcCommand({ VpcId: id }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.vpcs() }),
  });
}
