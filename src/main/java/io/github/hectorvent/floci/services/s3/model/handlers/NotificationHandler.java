package main.java.io.github.hectorvent.floci.services.s3.model.handlers;

import jakarta.ws.rs.core.Response;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.RequestContext;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.services.s3.model.TopicNotification;
import io.github.hectorvent.floci.services.s3.model.QueueNotification;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import jakarta.ws.rs.core.MediaType;

public class NotificationHandler implements Handler {

    @Override
    public Response handleGet(S3Service service, RequestContext context) {
        try {
            NotificationConfiguration config = service.getBucketNotificationConfiguration(context.getBucket());
            XmlBuilder xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("NotificationConfiguration", AwsNamespaces.S3);

            for (QueueNotification qn : config.getQueueConfigurations()) {
                xml.start("QueueConfiguration")
                        .elem("Id", qn.id())
                        .elem("Queue", qn.queueArn());
                for (String event : qn.events()) {
                    xml.elem("Event", event);
                }
                // Nota: Certifique-se de expor ou mover appendFilterRules() para utilitários
                appendFilterRules(xml, qn.filterRules());
                xml.end("QueueConfiguration");
            }

            for (TopicNotification tn : config.getTopicConfigurations()) {
                xml.start("TopicConfiguration")
                        .elem("Id", tn.id())
                        .elem("Topic", tn.topicArn());
                for (String event : tn.events()) {
                    xml.elem("Event", event);
                }
                appendFilterRules(xml, tn.filterRules());
                xml.end("TopicConfiguration");
            }

            for (LambdaNotification ln : config.getLambdaFunctionConfigurations()) {
                xml.start("CloudFunctionConfiguration")
                        .elem("Id", ln.id())
                        .elem("CloudFunction", ln.functionArn());
                for (String event : ln.events()) {
                    xml.elem("Event", event);
                }
                appendFilterRules(xml, ln.filterRules());
                xml.end("CloudFunctionConfiguration");
            }

            xml.end("NotificationConfiguration");
            return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
        } catch (AwsException e) {
            // Nota: Certifique-se de remapear xmlErrorResponse() apropriadamente
            return xmlErrorResponse(e);
        }
    }
}