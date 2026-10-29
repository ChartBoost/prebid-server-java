package org.prebid.server.spring.config.bidder;

import org.prebid.server.bidder.BidderDeps;
import org.prebid.server.bidder.chartboost.ChartboostBidder;
import org.prebid.server.currency.CurrencyConversionService;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.spring.config.bidder.model.BidderConfigurationProperties;
import org.prebid.server.spring.config.bidder.util.BidderDepsAssembler;
import org.prebid.server.spring.env.YamlPropertySourceFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@PropertySource(value = "classpath:/bidder-config/chartboost.yaml", factory = YamlPropertySourceFactory.class)
public class ChartboostConfiguration {

    private static final String BIDDER_NAME = "chartboost";

    @Bean("chartboostConfigurationProperties")
    @ConfigurationProperties("adapters.chartboost")
    BidderConfigurationProperties configurationProperties() {
        return new BidderConfigurationProperties();
    }

    @Bean
    BidderDeps chartboostBidderDeps(BidderConfigurationProperties chartboostConfigurationProperties,
                                    CurrencyConversionService currencyConversionService,
                                    JacksonMapper mapper) {

        return BidderDepsAssembler.forBidder(BIDDER_NAME)
                .withConfig(chartboostConfigurationProperties)
                .bidderCreator(config ->
                        new ChartboostBidder(config.getEndpoint(), currencyConversionService, mapper))
                .assemble();
    }
}
