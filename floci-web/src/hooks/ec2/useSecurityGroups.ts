import {
  AuthorizeSecurityGroupEgressCommand,
  AuthorizeSecurityGroupIngressCommand,
  CreateSecurityGroupCommand,
  DeleteSecurityGroupCommand,
  DescribeSecurityGroupsCommand,
  RevokeSecurityGroupEgressCommand,
  RevokeSecurityGroupIngressCommand,
  type IpPermission,
} from '@aws-sdk/client-ec2';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useSecurityGroups() {
  return useQuery({
    queryKey: ec2Keys.securityGroups(),
    queryFn: async () => {
      const r = await ec2.send(new DescribeSecurityGroupsCommand({}));
      return r.SecurityGroups ?? [];
    },
  });
}

export function useSecurityGroup(id: string) {
  return useQuery({
    queryKey: ec2Keys.securityGroup(id),
    queryFn: async () => {
      const r = await ec2.send(
        new DescribeSecurityGroupsCommand({ GroupIds: [id] }),
      );
      return r.SecurityGroups?.[0] ?? null;
    },
    enabled: !!id,
  });
}

export function useCreateSecurityGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      groupName: string;
      description: string;
      vpcId?: string;
    }) => {
      await ec2.send(
        new CreateSecurityGroupCommand({
          GroupName: input.groupName,
          Description: input.description,
          VpcId: input.vpcId,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: ec2Keys.securityGroups() }),
  });
}

export function useDeleteSecurityGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await ec2.send(new DeleteSecurityGroupCommand({ GroupId: id }));
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: ec2Keys.securityGroups() }),
  });
}

export function useAuthorizeRule(direction: 'ingress' | 'egress') {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { groupId: string; rule: IpPermission }) => {
      if (direction === 'ingress') {
        await ec2.send(
          new AuthorizeSecurityGroupIngressCommand({
            GroupId: input.groupId,
            IpPermissions: [input.rule],
          }),
        );
      } else {
        await ec2.send(
          new AuthorizeSecurityGroupEgressCommand({
            GroupId: input.groupId,
            IpPermissions: [input.rule],
          }),
        );
      }
    },
    onSuccess: (_d, input) => {
      qc.invalidateQueries({ queryKey: ec2Keys.securityGroup(input.groupId) });
      qc.invalidateQueries({ queryKey: ec2Keys.securityGroups() });
    },
  });
}

export function useRevokeRule(direction: 'ingress' | 'egress') {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { groupId: string; rule: IpPermission }) => {
      if (direction === 'ingress') {
        await ec2.send(
          new RevokeSecurityGroupIngressCommand({
            GroupId: input.groupId,
            IpPermissions: [input.rule],
          }),
        );
      } else {
        await ec2.send(
          new RevokeSecurityGroupEgressCommand({
            GroupId: input.groupId,
            IpPermissions: [input.rule],
          }),
        );
      }
    },
    onSuccess: (_d, input) => {
      qc.invalidateQueries({ queryKey: ec2Keys.securityGroup(input.groupId) });
      qc.invalidateQueries({ queryKey: ec2Keys.securityGroups() });
    },
  });
}
