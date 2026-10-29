package org.prebid.server.proto.openrtb.ext.request.chartboost;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

/**
 * Defines the contract for bidrequest.imp[i].ext.prebid.bidder.chartboost
 * <p>
 * These are forwarded untouched; they are read on the Chartboost side.
 */
@Value(staticConstructor = "of")
public class ExtImpChartboost {

    @JsonProperty("app_id")
    String appId;

    @JsonProperty("location_name")
    String locationName;
}
