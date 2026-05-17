import {
  CreateRouteCommand,
  CreateRouteTableCommand,
  DeleteRouteCommand,
  DeleteRouteTableCommand,
  DescribeRouteTablesCommand,
} from '@aws-sdk/client-ec2';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useRouteTables() {
  return useQuery({
    queryKey: ec2Keys.routeTables(),
    queryFn: async () => {
      const r = await ec2.send(new DescribeRouteTablesCommand({}));
      return r.RouteTables ?? [];
    },
  });
}

export function useRouteTable(id: string) {
  return useQuery({
    queryKey: ec2Keys.routeTable(id),
    queryFn: async () => {
      const r = await ec2.send(
        new DescribeRouteTablesCommand({ RouteTableIds: [id] }),
      );
      return r.RouteTables?.[0] ?? null;
    },
    enabled: !!id,
  });
}

export function useCreateRouteTable() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (vpcId: string) => {
      await ec2.send(new CreateRouteTableCommand({ VpcId: vpcId }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.routeTables() }),
  });
}

export function useDeleteRouteTable() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await ec2.send(new DeleteRouteTableCommand({ RouteTableId: id }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.routeTables() }),
  });
}

export function useCreateRoute(routeTableId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      destinationCidr: string;
      gatewayId?: string;
      natGatewayId?: string;
      instanceId?: string;
    }) => {
      await ec2.send(
        new CreateRouteCommand({
          RouteTableId: routeTableId,
          DestinationCidrBlock: input.destinationCidr,
          GatewayId: input.gatewayId,
          NatGatewayId: input.natGatewayId,
          InstanceId: input.instanceId,
        }),
      );
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ec2Keys.routeTable(routeTableId) });
      qc.invalidateQueries({ queryKey: ec2Keys.routeTables() });
    },
  });
}

export function useDeleteRoute(routeTableId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (destinationCidr: string) => {
      await ec2.send(
        new DeleteRouteCommand({
          RouteTableId: routeTableId,
          DestinationCidrBlock: destinationCidr,
        }),
      );
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ec2Keys.routeTable(routeTableId) });
      qc.invalidateQueries({ queryKey: ec2Keys.routeTables() });
    },
  });
}
