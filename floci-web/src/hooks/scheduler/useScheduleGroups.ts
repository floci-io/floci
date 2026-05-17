import {
  CreateScheduleGroupCommand,
  DeleteScheduleGroupCommand,
  GetScheduleGroupCommand,
  ListScheduleGroupsCommand,
  type ScheduleGroupSummary,
} from '@aws-sdk/client-scheduler';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { scheduler } from '@/lib/schedulerClient';
import { schedulerKeys } from '@/lib/queryKeys';

export function useScheduleGroups() {
  return useQuery({
    queryKey: schedulerKeys.groups(),
    queryFn: async (): Promise<ScheduleGroupSummary[]> => {
      const all: ScheduleGroupSummary[] = [];
      let token: string | undefined;
      do {
        const r = await scheduler.send(
          new ListScheduleGroupsCommand({ NextToken: token, MaxResults: 100 }),
        );
        all.push(...(r.ScheduleGroups ?? []));
        token = r.NextToken;
      } while (token);
      return all;
    },
  });
}

export function useScheduleGroup(name: string) {
  return useQuery({
    queryKey: schedulerKeys.group(name),
    queryFn: async () => {
      const r = await scheduler.send(
        new GetScheduleGroupCommand({ Name: name }),
      );
      return r;
    },
    enabled: !!name,
  });
}

export function useCreateScheduleGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      name: string;
      tags?: Array<{ Key: string; Value: string }>;
    }) => {
      await scheduler.send(
        new CreateScheduleGroupCommand({
          Name: input.name,
          Tags: input.tags,
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: schedulerKeys.groups() }),
  });
}

export function useDeleteScheduleGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (name: string) => {
      await scheduler.send(new DeleteScheduleGroupCommand({ Name: name }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: schedulerKeys.groups() }),
  });
}
