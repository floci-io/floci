import { Navigate, Route, Routes } from 'react-router-dom';
import AppShell from './components/AppShell';
import BucketsPage from './routes/BucketsPage';
import BucketLayout from './routes/BucketLayout';
import ObjectsTab from './routes/tabs/ObjectsTab';
import VersionsTab from './routes/tabs/VersionsTab';
import MultipartTab from './routes/tabs/MultipartTab';
import TagsTab from './routes/tabs/TagsTab';
import LifecycleTab from './routes/tabs/LifecycleTab';
import CorsTab from './routes/tabs/CorsTab';
import PolicyTab from './routes/tabs/PolicyTab';
import LockTab from './routes/tabs/LockTab';
import EncryptionTab from './routes/tabs/EncryptionTab';
import PublicAccessTab from './routes/tabs/PublicAccessTab';
import NotificationsTab from './routes/tabs/NotificationsTab';
import ObjectDetailPage from './routes/ObjectDetailPage';
import SelectPage from './routes/SelectPage';
import RdsLayout from './routes/rds/RdsLayout';
import InstancesPage from './routes/rds/InstancesPage';
import InstanceDetailPage from './routes/rds/InstanceDetailPage';
import ClustersPage from './routes/rds/ClustersPage';
import ClusterDetailPage from './routes/rds/ClusterDetailPage';
import ParameterGroupsPage from './routes/rds/ParameterGroupsPage';
import ParameterGroupDetailPage from './routes/rds/ParameterGroupDetailPage';
import FunctionsPage from './routes/lambda/FunctionsPage';
import FunctionLayout from './routes/lambda/FunctionLayout';
import LambdaConfigurationTab from './routes/lambda/tabs/ConfigurationTab';
import LambdaCodeTab from './routes/lambda/tabs/CodeTab';
import LambdaInvokeTab from './routes/lambda/tabs/InvokeTab';
import LambdaVersionsAliasesTab from './routes/lambda/tabs/VersionsAliasesTab';
import LambdaEventSourcesTab from './routes/lambda/tabs/EventSourcesTab';
import LambdaPermissionsTab from './routes/lambda/tabs/PermissionsTab';
import LambdaFunctionUrlTab from './routes/lambda/tabs/FunctionUrlTab';
import LambdaConcurrencyTab from './routes/lambda/tabs/ConcurrencyTab';
import LambdaTagsTab from './routes/lambda/tabs/TagsTab';
import Ec2Layout from './routes/ec2/Ec2Layout';
import InstancesTab from './routes/ec2/tabs/InstancesTab';
import Ec2InstanceDetailPage from './routes/ec2/InstanceDetailPage';
import AmisTab from './routes/ec2/tabs/AmisTab';
import KeyPairsTab from './routes/ec2/tabs/KeyPairsTab';
import SecurityGroupsTab from './routes/ec2/tabs/SecurityGroupsTab';
import SecurityGroupDetailPage from './routes/ec2/SecurityGroupDetailPage';
import VpcsTab from './routes/ec2/tabs/VpcsTab';
import SubnetsTab from './routes/ec2/tabs/SubnetsTab';
import InternetGatewaysTab from './routes/ec2/tabs/InternetGatewaysTab';
import RouteTablesTab from './routes/ec2/tabs/RouteTablesTab';
import RouteTableDetailPage from './routes/ec2/RouteTableDetailPage';
import ElasticIpsTab from './routes/ec2/tabs/ElasticIpsTab';
import VolumesTab from './routes/ec2/tabs/VolumesTab';
import SchedulerLayout from './routes/scheduler/SchedulerLayout';
import SchedulesTab from './routes/scheduler/tabs/SchedulesTab';
import ScheduleGroupsTab from './routes/scheduler/tabs/ScheduleGroupsTab';
import ScheduleDetailPage from './routes/scheduler/ScheduleDetailPage';
import ScheduleGroupDetailPage from './routes/scheduler/ScheduleGroupDetailPage';

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<Navigate to="/s3" replace />} />

        {/* S3 */}
        <Route path="/s3" element={<BucketsPage />} />
        <Route path="/s3/b/:bucket" element={<BucketLayout />}>
          <Route index element={<ObjectsTab />} />
          <Route path="objects" element={<ObjectsTab />} />
          <Route path="versions" element={<VersionsTab />} />
          <Route path="multipart" element={<MultipartTab />} />
          <Route path="tags" element={<TagsTab />} />
          <Route path="lifecycle" element={<LifecycleTab />} />
          <Route path="cors" element={<CorsTab />} />
          <Route path="policy" element={<PolicyTab />} />
          <Route path="lock" element={<LockTab />} />
          <Route path="encryption" element={<EncryptionTab />} />
          <Route path="public-access" element={<PublicAccessTab />} />
          <Route path="notifications" element={<NotificationsTab />} />
        </Route>
        <Route path="/s3/b/:bucket/object" element={<ObjectDetailPage />} />
        <Route path="/s3/b/:bucket/select" element={<SelectPage />} />

        {/* RDS */}
        <Route path="/rds" element={<RdsLayout />}>
          <Route index element={<Navigate to="instances" replace />} />
          <Route path="instances" element={<InstancesPage />} />
          <Route
            path="instances/:identifier"
            element={<InstanceDetailPage />}
          />
          <Route path="clusters" element={<ClustersPage />} />
          <Route
            path="clusters/:identifier"
            element={<ClusterDetailPage />}
          />
          <Route
            path="parameter-groups"
            element={<ParameterGroupsPage />}
          />
          <Route
            path="parameter-groups/:name"
            element={<ParameterGroupDetailPage />}
          />
        </Route>

        {/* Lambda */}
        <Route path="/lambda" element={<FunctionsPage />} />
        <Route path="/lambda/functions/:name" element={<FunctionLayout />}>
          <Route index element={<Navigate to="configuration" replace />} />
          <Route path="configuration" element={<LambdaConfigurationTab />} />
          <Route path="code" element={<LambdaCodeTab />} />
          <Route path="invoke" element={<LambdaInvokeTab />} />
          <Route path="versions" element={<LambdaVersionsAliasesTab />} />
          <Route path="event-sources" element={<LambdaEventSourcesTab />} />
          <Route path="permissions" element={<LambdaPermissionsTab />} />
          <Route path="url" element={<LambdaFunctionUrlTab />} />
          <Route path="concurrency" element={<LambdaConcurrencyTab />} />
          <Route path="tags" element={<LambdaTagsTab />} />
        </Route>

        {/* EC2 */}
        <Route path="/ec2" element={<Ec2Layout />}>
          <Route index element={<Navigate to="instances" replace />} />
          <Route path="instances" element={<InstancesTab />} />
          <Route path="amis" element={<AmisTab />} />
          <Route path="key-pairs" element={<KeyPairsTab />} />
          <Route path="security-groups" element={<SecurityGroupsTab />} />
          <Route path="vpcs" element={<VpcsTab />} />
          <Route path="subnets" element={<SubnetsTab />} />
          <Route path="internet-gateways" element={<InternetGatewaysTab />} />
          <Route path="route-tables" element={<RouteTablesTab />} />
          <Route path="elastic-ips" element={<ElasticIpsTab />} />
          <Route path="volumes" element={<VolumesTab />} />
        </Route>
        <Route
          path="/ec2/instances/:id"
          element={<Ec2InstanceDetailPage />}
        />
        <Route
          path="/ec2/security-groups/:id"
          element={<SecurityGroupDetailPage />}
        />
        <Route
          path="/ec2/route-tables/:id"
          element={<RouteTableDetailPage />}
        />

        {/* Scheduler */}
        <Route path="/scheduler" element={<SchedulerLayout />}>
          <Route index element={<Navigate to="schedules" replace />} />
          <Route path="schedules" element={<SchedulesTab />} />
          <Route path="groups" element={<ScheduleGroupsTab />} />
        </Route>
        <Route
          path="/scheduler/schedules/:groupName/:name"
          element={<ScheduleDetailPage />}
        />
        <Route
          path="/scheduler/groups/:name"
          element={<ScheduleGroupDetailPage />}
        />
      </Route>
    </Routes>
  );
}
