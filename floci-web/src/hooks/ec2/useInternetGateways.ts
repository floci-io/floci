import {
  AttachInternetGatewayCommand,
  CreateInternetGatewayCommand,
  DeleteInternetGatewayCommand,
  DescribeInternetGatewaysCommand,
  DetachInternetGatewayCommand,
} from '@aws-sdk/client-ec2';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useInternetGateways() {
  return useQuery({
    queryKey: ec2Keys.internetGateways(),
    queryFn: async () => {
      const r = await ec2.send(new DescribeInternetGatewaysCommand({}));
      return r.InternetGateways ?? [];
    },
  });
}

export function useCreateInternetGateway() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      await ec2.send(new CreateInternetGatewayCommand({}));
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: ec2Keys.internetGateways() }),
  });
}

export function useAttachInternetGateway() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { gatewayId: string; vpcId: string }) => {
      await ec2.send(
        new AttachInternetGatewayCommand({
          InternetGatewayId: input.gatewayId,
          VpcId: input.vpcId,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: ec2Keys.internetGateways() }),
  });
}

export function useDetachInternetGateway() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { gatewayId: string; vpcId: string }) => {
      await ec2.send(
        new DetachInternetGatewayCommand({
          InternetGatewayId: input.gatewayId,
          VpcId: input.vpcId,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: ec2Keys.internetGateways() }),
  });
}

export function useDeleteInternetGateway() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await ec2.send(
        new DeleteInternetGatewayCommand({ InternetGatewayId: id }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: ec2Keys.internetGateways() }),
  });
}
