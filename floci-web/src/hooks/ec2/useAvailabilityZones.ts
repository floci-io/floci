import {
  DescribeAvailabilityZonesCommand,
  DescribeInstanceTypesCommand,
} from '@aws-sdk/client-ec2';
import { useQuery } from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useAvailabilityZones() {
  return useQuery({
    queryKey: ec2Keys.availabilityZones(),
    queryFn: async () => {
      const r = await ec2.send(new DescribeAvailabilityZonesCommand({}));
      return r.AvailabilityZones ?? [];
    },
  });
}

export function useInstanceTypes() {
  return useQuery({
    queryKey: ec2Keys.instanceTypes(),
    queryFn: async () => {
      const r = await ec2.send(
        new DescribeInstanceTypesCommand({ MaxResults: 100 }),
      );
      return r.InstanceTypes ?? [];
    },
  });
}
