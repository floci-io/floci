import {
  CreateScheduleCommand,
  DeleteScheduleCommand,
  GetScheduleCommand,
  ListSchedulesCommand,
  UpdateScheduleCommand,
  type CreateScheduleCommandInput,
  type ScheduleSummary,
  type UpdateScheduleCommandInput,
} from '@aws-sdk/client-scheduler';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { scheduler } from '@/lib/schedulerClient';
import { schedulerKeys } from '@/lib/queryKeys';

export function useSchedules(groupName?: string) {
  return useQuery({
    queryKey: schedulerKeys.schedules(groupName),
    queryFn: async (): Promise<ScheduleSummary[]> => {
      const all: ScheduleSummary[] = [];
      let token: string | undefined;
      do {
        const r = await scheduler.send(
          new ListSchedulesCommand({
            GroupName: groupName,
            NextToken: token,
            MaxResults: 100,
          }),
        );
        all.push(...(r.Schedules ?? []));
        token = r.NextToken;
      } while (token);
      return all;
    },
  });
}

export function useSchedule(name: string, groupName: string) {
  return useQuery({
    queryKey: schedulerKeys.schedule(name, groupName),
    queryFn: async () => {
      const r = await scheduler.send(
        new GetScheduleCommand({ Name: name, GroupName: groupName }),
      );
      return r;
    },
    enabled: !!name,
  });
}

export function useCreateSchedule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateScheduleCommandInput) => {
      await scheduler.send(new CreateScheduleCommand(input));
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: ['scheduler', 'schedules'] }),
  });
}

export function useUpdateSchedule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: UpdateScheduleCommandInput) => {
      await scheduler.send(new UpdateScheduleCommand(input));
    },
    onSuccess: (_d, input) => {
      qc.invalidateQueries({ queryKey: ['scheduler', 'schedules'] });
      if (input.Name && input.GroupName) {
        qc.invalidateQueries({
          queryKey: schedulerKeys.schedule(input.Name, input.GroupName),
        });
      }
    },
  });
}

export function useDeleteSchedule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { name: string; groupName: string }) => {
      await scheduler.send(
        new DeleteScheduleCommand({
          Name: input.name,
          GroupName: input.groupName,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: ['scheduler', 'schedules'] }),
  });
}
