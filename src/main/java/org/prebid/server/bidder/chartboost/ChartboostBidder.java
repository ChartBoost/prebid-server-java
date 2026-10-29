package org.prebid.server.bidder.chartboost;

import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.MultiMap;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderCall;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Price;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.currency.CurrencyConversionService;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.BidderUtil;
import org.prebid.server.util.HttpUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Chartboost {@link Bidder} implementation.
 * <p>
 * Forwards the bid request to the Chartboost endpoint verbatim, except that non-USD bid floors are converted
 * to USD, since Chartboost bids exclusively in USD and interprets imp.bidfloor as a USD amount.
 */
public class ChartboostBidder implements Bidder<BidRequest> {

    private static final String BIDDER_CURRENCY = "USD";
    private static final String X_OPENRTB_VERSION = "2.6";

    private final String endpointUrl;
    private final CurrencyConversionService currencyConversionService;
    private final JacksonMapper mapper;

    public ChartboostBidder(String endpointUrl,
                            CurrencyConversionService currencyConversionService,
                            JacksonMapper mapper) {

        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.currencyConversionService = Objects.requireNonNull(currencyConversionService);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest bidRequest) {
        final List<BidderError> errors = new ArrayList<>();
        final List<Imp> modifiedImps = new ArrayList<>();

        for (Imp imp : bidRequest.getImp()) {
            try {
                modifiedImps.add(convertBidFloorToUsd(imp, bidRequest));
            } catch (PreBidException e) {
                errors.add(BidderError.badInput(e.getMessage()));
            }
        }

        if (modifiedImps.isEmpty()) {
            return Result.withErrors(errors);
        }

        final BidRequest modifiedBidRequest = bidRequest.toBuilder().imp(modifiedImps).build();
        final HttpRequest<BidRequest> httpRequest = BidderUtil.defaultRequest(
                modifiedBidRequest, makeHeaders(), endpointUrl, mapper);

        return Result.of(List.of(httpRequest), errors);
    }

    private Imp convertBidFloorToUsd(Imp imp, BidRequest bidRequest) {
        final Price bidFloorPrice = Price.of(imp.getBidfloorcur(), imp.getBidfloor());
        if (!BidderUtil.shouldConvertBidFloor(bidFloorPrice, BIDDER_CURRENCY)) {
            return imp;
        }

        final BigDecimal convertedBidFloor;
        try {
            convertedBidFloor = currencyConversionService.convertCurrency(
                    bidFloorPrice.getValue(), bidRequest, bidFloorPrice.getCurrency(), BIDDER_CURRENCY);
        } catch (PreBidException e) {
            throw new PreBidException("Unable to convert bid floor of imp %s from %s to %s: %s"
                    .formatted(imp.getId(), bidFloorPrice.getCurrency(), BIDDER_CURRENCY, e.getMessage()));
        }

        return imp.toBuilder()
                .bidfloor(convertedBidFloor)
                .bidfloorcur(BIDDER_CURRENCY)
                .build();
    }

    private static MultiMap makeHeaders() {
        return HttpUtil.headers().add(HttpUtil.X_OPENRTB_VERSION_HEADER, X_OPENRTB_VERSION);
    }

    @Override
    public Result<List<BidderBid>> makeBids(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            return extractBids(bidResponse);
        } catch (DecodeException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private static Result<List<BidderBid>> extractBids(BidResponse bidResponse) {
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            return Result.empty();
        }

        final String currency = StringUtils.defaultIfEmpty(bidResponse.getCur(), BIDDER_CURRENCY);
        final List<BidderError> errors = new ArrayList<>();
        final List<BidderBid> bidderBids = new ArrayList<>();

        for (SeatBid seatBid : bidResponse.getSeatbid()) {
            if (seatBid == null || CollectionUtils.isEmpty(seatBid.getBid())) {
                continue;
            }
            for (Bid bid : seatBid.getBid()) {
                if (bid == null) {
                    continue;
                }
                try {
                    bidderBids.add(BidderBid.of(bid, resolveBidType(bid), currency));
                } catch (PreBidException e) {
                    errors.add(BidderError.badServerResponse(e.getMessage()));
                }
            }
        }

        return Result.of(bidderBids, errors);
    }

    private static BidType resolveBidType(Bid bid) {
        final Integer mtype = bid.getMtype();
        if (mtype == null) {
            throw new PreBidException("Missing MType for bid " + bid.getId());
        }
        return switch (mtype) {
            case 1 -> BidType.banner;
            case 2 -> BidType.video;
            default -> throw new PreBidException("Unsupported MType %d for bid %s".formatted(mtype, bid.getId()));
        };
    }
}
