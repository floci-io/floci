import { DescribeImagesCommand } from '@aws-sdk/client-ec2';
import { useQuery } from '@tanstack/react-query';
import { ec2 } from '@/lib/ec2Client';
import { ec2Keys } from '@/lib/queryKeys';

export function useImages() {
  return useQuery({
    queryKey: ec2Keys.images(),
    queryFn: async () => {
      const r = await ec2.send(new DescribeImagesCommand({}));
      return r.Images ?? [];
    },
  });
}
