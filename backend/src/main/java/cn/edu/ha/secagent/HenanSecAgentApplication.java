package cn.edu.ha.secagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HenanSecAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(HenanSecAgentApplication.class, args);
    }
}

