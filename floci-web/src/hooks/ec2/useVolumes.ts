import {
  CreateVolumeCommand,
  DeleteVolumeCommand,
  DescribeVolumesCommand,
  VolumeType,
} from '@aws-sdk/client-ec2';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useVolumes() {
  return useQuery({
    queryKey: ec2Keys.volumes(),
    queryFn: async () => {
      const r = await ec2.send(new DescribeVolumesCommand({}));
      return r.Volumes ?? [];
    },
  });
}

export function useCreateVolume() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      sizeGb: number;
      availabilityZone: string;
      type?: VolumeType;
    }) => {
      await ec2.send(
        new CreateVolumeCommand({
          Size: input.sizeGb,
          AvailabilityZone: input.availabilityZone,
          VolumeType: input.type ?? VolumeType.gp3,
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.volumes() }),
  });
}

export function useDeleteVolume() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await ec2.send(new DeleteVolumeCommand({ VolumeId: id }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ec2Keys.volumes() }),
  });
}
