package datawave.microservice.audit.accumulo.config;

import datawave.microservice.audit.accumulo.AccumuloAuditor;
import datawave.microservice.audit.common.AuditMessageHandler;
import datawave.microservice.audit.common.Auditor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AccumuloAuditProperties.class)
@ConditionalOnProperty(name = "dw.audit.accumulo.enabled", havingValue = "true")
public class AccumuloAuditConfig {
    
    @Autowired
    AccumuloAuditProperties accumuloAuditProperties;
    
    @Bean
    Queue accumuloAuditQueue() {
        return new Queue(accumuloAuditProperties.getQueueName(), accumuloAuditProperties.isDurable());
    }
    
    @Bean
    Binding accumuloAuditBinding(Queue accumuloAuditQueue, FanoutExchange auditExchange) {
        return BindingBuilder.bind(accumuloAuditQueue).to(auditExchange);
    }
    
    @Bean
    SimpleMessageListenerContainer accumuloAuditContainer(ConnectionFactory connectionFactory, MessageListenerAdapter accumuloAuditListenerAdapter) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setQueueNames(accumuloAuditProperties.getQueueName());
        container.setMessageListener(accumuloAuditListenerAdapter);
        return container;
    }
    
    @Bean
    Auditor accumuloAuditor() {
        return new AccumuloAuditor();
    }
    
    @Bean
    AuditMessageHandler accumuloAuditMessageHandler(Auditor accumuloAuditor) {
        return new AuditMessageHandler(accumuloAuditor);
    }
    
    @Bean
    MessageListenerAdapter accumuloAuditListenerAdapter(AuditMessageHandler accumuloAuditMessageHandler) {
        return new MessageListenerAdapter(accumuloAuditMessageHandler, accumuloAuditMessageHandler.LISTENER_METHOD);
    }
}