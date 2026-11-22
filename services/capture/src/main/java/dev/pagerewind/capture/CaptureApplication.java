package dev.pagerewind.capture;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CaptureApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaptureApplication.class, args);
    }

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** In a pod: the service account's token. On a laptop: the current kubeconfig context. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean
    LogBuffer logBuffer(CaptureProperties properties) {
        return new LogBuffer(new Redactor(properties.redactPatterns()), properties.window(),
                properties.maxLinesPerContainer(), properties.maxBufferSize().toBytes(), properties.deletedPodRetention());
    }

    @Bean
    ChangeRanking changeRanking(CaptureProperties properties) {
        return new ChangeRanking(properties.lookBack());
    }
}
