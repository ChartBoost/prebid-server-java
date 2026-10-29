package org.prebid.server.bidder.chartboost;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.prebid.server.VertxTest;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderCall;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.HttpResponse;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.currency.CurrencyConversionService;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.chartboost.ExtImpChartboost;
import org.prebid.server.proto.openrtb.ext.response.BidType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static java.util.Collections.singletonList;
import static java.util.function.UnaryOperator.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
public class ChartboostBidderTest extends VertxTest {

    private static final String ENDPOINT_URL = "https://test.endpoint.com/auction";

    @Mock
    private CurrencyConversionService currencyConversionService;

    private ChartboostBidder target;

    @BeforeEach
    public void setUp() {
        target = new ChartboostBidder(ENDPOINT_URL, currencyConversionService, jacksonMapper);
    }

    @Test
    public void creationShouldFailOnInvalidEndpointUrl() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ChartboostBidder("invalid_url", currencyConversionService, jacksonMapper));
    }

    @Test
    public void makeHttpRequestsShouldCreateSingleRequestWithExpectedHeaders() {
        // given
        final BidRequest bidRequest = givenBidRequest(givenImp(identity()));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).hasSize(1).first().satisfies(request -> {
            assertThat(request.getUri()).isEqualTo(ENDPOINT_URL);
            assertThat(request.getImpIds()).containsExactly("123");
            assertThat(request.getHeaders())
                    .extracting(Map.Entry::getKey, Map.Entry::getValue)
                    .containsExactlyInAnyOrder(
                            tuple("Content-Type", "application/json;charset=utf-8"),
                            tuple("Accept", "application/json"),
                            tuple("x-openrtb-version", "2.6"));
        });
    }

    @Test
    public void makeHttpRequestsShouldForwardRequestVerbatimWhenBidFloorIsInUsd() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                givenImp(imp -> imp.bidfloor(BigDecimal.TEN).bidfloorcur("USD")));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .containsExactly(bidRequest);
        verifyNoInteractions(currencyConversionService);
    }

    @Test
    public void makeHttpRequestsShouldNotConvertBidFloorWhenCurrencyIsUsdInDifferentCase() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                givenImp(imp -> imp.bidfloor(BigDecimal.TEN).bidfloorcur("usd")));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .flatExtracting(BidRequest::getImp)
                .extracting(Imp::getBidfloor, Imp::getBidfloorcur)
                .containsExactly(tuple(BigDecimal.TEN, "usd"));
        verifyNoInteractions(currencyConversionService);
    }

    @Test
    public void makeHttpRequestsShouldNotConvertBidFloorWhenCurrencyIsMissing() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                givenImp(imp -> imp.bidfloor(BigDecimal.TEN).bidfloorcur(null)));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .flatExtracting(BidRequest::getImp)
                .extracting(Imp::getBidfloor, Imp::getBidfloorcur)
                .containsExactly(tuple(BigDecimal.TEN, null));
        verifyNoInteractions(currencyConversionService);
    }

    @Test
    public void makeHttpRequestsShouldNotConvertBidFloorWhenBidFloorIsNotPositive() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                givenImp(imp -> imp.bidfloor(BigDecimal.ZERO).bidfloorcur("EUR")),
                givenImp(imp -> imp.bidfloor(null).bidfloorcur("EUR")));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .flatExtracting(BidRequest::getImp)
                .extracting(Imp::getBidfloor, Imp::getBidfloorcur)
                .containsExactly(tuple(BigDecimal.ZERO, "EUR"), tuple(null, "EUR"));
        verifyNoInteractions(currencyConversionService);
    }

    @Test
    public void makeHttpRequestsShouldConvertBidFloorToUsdWhenCurrencyIsDifferent() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                givenImp(imp -> imp.bidfloor(BigDecimal.TEN).bidfloorcur("EUR")));
        given(currencyConversionService.convertCurrency(BigDecimal.TEN, bidRequest, "EUR", "USD"))
                .willReturn(BigDecimal.ONE);

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(HttpRequest::getPayload)
                .flatExtracting(BidRequest::getImp)
                .extracting(Imp::getBidfloor, Imp::getBidfloorcur)
                .containsExactly(tuple(BigDecimal.ONE, "USD"));
    }

    @Test
    public void makeHttpRequestsShouldReturnErrorWithoutRequestWhenBidFloorOfOnlyImpCannotBeConverted() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                givenImp(imp -> imp.bidfloor(BigDecimal.TEN).bidfloorcur("UAH")));
        given(currencyConversionService.convertCurrency(any(), any(), any(), any())).willThrow(
                new PreBidException("Unable to convert from currency UAH to desired ad server currency USD"));

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).containsExactly(BidderError.badInput(
                "Unable to convert bid floor of imp 123 from UAH to USD: "
                        + "Unable to convert from currency UAH to desired ad server currency USD"));
    }

    @Test
    public void makeHttpRequestsShouldOmitImpAndAddErrorWhenBidFloorOfOneOfImpsCannotBeConverted() {
        // given
        final BidRequest bidRequest = givenBidRequest(
                givenImp(imp -> imp.id("imp1").bidfloor(BigDecimal.TEN).bidfloorcur("UAH")),
                givenImp(imp -> imp.id("imp2").bidfloor(BigDecimal.TEN).bidfloorcur("EUR")),
                givenImp(imp -> imp.id("imp3")));
        given(currencyConversionService.convertCurrency(BigDecimal.TEN, bidRequest, "UAH", "USD"))
                .willThrow(new PreBidException("no rate"));
        given(currencyConversionService.convertCurrency(BigDecimal.TEN, bidRequest, "EUR", "USD"))
                .willReturn(BigDecimal.ONE);

        // when
        final Result<List<HttpRequest<BidRequest>>> result = target.makeHttpRequests(bidRequest);

        // then
        assertThat(result.getErrors()).containsExactly(BidderError.badInput(
                "Unable to convert bid floor of imp imp1 from UAH to USD: no rate"));
        assertThat(result.getValue()).hasSize(1).first().satisfies(request -> {
            assertThat(request.getImpIds()).containsExactlyInAnyOrder("imp2", "imp3");
            assertThat(request.getPayload().getImp())
                    .extracting(Imp::getId, Imp::getBidfloor, Imp::getBidfloorcur)
                    .containsExactly(
                            tuple("imp2", BigDecimal.ONE, "USD"),
                            tuple("imp3", null, null));
        });
    }

    @Test
    public void makeBidsShouldReturnErrorWhenResponseBodyCannotBeParsed() {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall("invalid");

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getValue()).isEmpty();
        assertThat(result.getErrors()).hasSize(1)
                .allSatisfy(error -> {
                    assertThat(error.getType()).isEqualTo(BidderError.Type.bad_server_response);
                    assertThat(error.getMessage()).startsWith("Failed to decode: Unrecognized token");
                });
    }

    @Test
    public void makeBidsShouldReturnEmptyListWhenBidResponseIsNull() throws JsonProcessingException {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(mapper.writeValueAsString(null));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).isEmpty();
    }

    @Test
    public void makeBidsShouldReturnEmptyListWhenSeatBidIsEmpty() throws JsonProcessingException {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                mapper.writeValueAsString(BidResponse.builder().seatbid(List.of()).build()));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue()).isEmpty();
    }

    @Test
    public void makeBidsShouldReturnBannerBidWhenMtypeIsOne() throws JsonProcessingException {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                mapper.writeValueAsString(givenBidResponse(bid -> bid.id("bid1").impid("123").mtype(1))));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(BidderBid::getBid, BidderBid::getType, BidderBid::getBidCurrency)
                .containsExactly(tuple(Bid.builder().id("bid1").impid("123").mtype(1).build(), BidType.banner, "USD"));
    }

    @Test
    public void makeBidsShouldReturnVideoBidWhenMtypeIsTwo() throws JsonProcessingException {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(
                mapper.writeValueAsString(givenBidResponse(bid -> bid.id("bid1").impid("123").mtype(2))));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(BidderBid::getType)
                .containsExactly(BidType.video);
    }

    @Test
    public void makeBidsShouldUseResponseCurrencyWhenPresent() throws JsonProcessingException {
        // given
        final BidResponse bidResponse = givenBidResponse(bid -> bid.id("bid1").impid("123").mtype(1))
                .toBuilder().cur("EUR").build();
        final BidderCall<BidRequest> httpCall = givenHttpCall(mapper.writeValueAsString(bidResponse));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getValue())
                .extracting(BidderBid::getBidCurrency)
                .containsExactly("EUR");
    }

    @Test
    public void makeBidsShouldReturnErrorsForBidsWithUnsupportedOrMissingMtypeAndKeepValidOnes()
            throws JsonProcessingException {
        // given
        final BidderCall<BidRequest> httpCall = givenHttpCall(mapper.writeValueAsString(givenBidResponse(
                bid -> bid.id("bid1").impid("123").mtype(1),
                bid -> bid.id("bid2").impid("123").mtype(3),
                bid -> bid.id("bid3").impid("123").mtype(4),
                bid -> bid.id("bid4").impid("123").mtype(null))));

        // when
        final Result<List<BidderBid>> result = target.makeBids(httpCall, null);

        // then
        assertThat(result.getValue())
                .extracting(bidderBid -> bidderBid.getBid().getId(), BidderBid::getType)
                .containsExactly(tuple("bid1", BidType.banner));
        assertThat(result.getErrors()).containsExactly(
                BidderError.badServerResponse("Unsupported MType 3 for bid bid2"),
                BidderError.badServerResponse("Unsupported MType 4 for bid bid3"),
                BidderError.badServerResponse("Missing MType for bid bid4"));
    }

    private static BidRequest givenBidRequest(Imp... imps) {
        return BidRequest.builder()
                .id("request-id")
                .cur(List.of("USD"))
                .imp(Arrays.stream(imps).toList())
                .build();
    }

    private static Imp givenImp(UnaryOperator<Imp.ImpBuilder> impCustomizer) {
        return impCustomizer.apply(Imp.builder()
                        .id("123")
                        .banner(Banner.builder().w(320).h(50).build())
                        .ext(mapper.valueToTree(ExtPrebid.of(null, ExtImpChartboost.of("app-id", "location")))))
                .build();
    }

    @SafeVarargs
    private static BidResponse givenBidResponse(UnaryOperator<Bid.BidBuilder>... bidCustomizers) {
        return BidResponse.builder()
                .seatbid(singletonList(SeatBid.builder()
                        .bid(Arrays.stream(bidCustomizers)
                                .map(bidCustomizer -> bidCustomizer.apply(Bid.builder()).build())
                                .toList())
                        .build()))
                .build();
    }

    private static BidderCall<BidRequest> givenHttpCall(String body) {
        return BidderCall.succeededHttp(
                HttpRequest.<BidRequest>builder().build(),
                HttpResponse.of(200, null, body),
                null);
    }
}
