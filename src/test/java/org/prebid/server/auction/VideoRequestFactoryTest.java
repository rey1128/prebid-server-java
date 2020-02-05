package org.prebid.server.auction;

import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Content;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.User;
import com.iab.openrtb.request.Video;
import com.iab.openrtb.request.video.BidRequestVideo;
import com.iab.openrtb.request.video.PodError;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.prebid.server.VertxTest;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.model.WithPodErrors;
import org.prebid.server.exception.InvalidRequestException;
import org.prebid.server.proto.openrtb.ext.ExtIncludeBrandCategory;
import org.prebid.server.proto.openrtb.ext.request.ExtBidRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidCache;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidCacheVastxml;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestTargeting;

import java.util.Arrays;

import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

public class VideoRequestFactoryTest extends VertxTest {

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private VideoStoredRequestProcessor videoStoredRequestProcessor;

    @Mock
    private AuctionRequestFactory auctionRequestFactory;

    private VideoRequestFactory target;

    @Mock
    private RoutingContext routingContext;
    @Mock
    private HttpServerRequest httpServerRequest;
    @Mock
    private TimeoutResolver timeoutResolver;

    @Before
    public void setUp() {
        given(routingContext.request()).willReturn(httpServerRequest);
        given(httpServerRequest.getParam(anyString())).willReturn("test");

        target = new VideoRequestFactory(Integer.MAX_VALUE, false, videoStoredRequestProcessor, auctionRequestFactory, timeoutResolver);
    }

    @Test
    public void shouldReturnFailedFutureIfRequestBodyIsMissing() {
        // given
        given(routingContext.getBody()).willReturn(null);

        // when
        final Future<?> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future.failed()).isTrue();
        assertThat(future.cause())
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Incoming request has no body");
    }

    @Test
    public void shouldReturnFailedFutureIfStoredRequestIsEnforcedAndIdIsNotProvided() {
        // given
        given(routingContext.getBody()).willReturn(Json.encodeToBuffer(BidRequestVideo.builder().build()));
        target = new VideoRequestFactory(Integer.MAX_VALUE, true, videoStoredRequestProcessor, auctionRequestFactory, timeoutResolver);

        // when
        final Future<?> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future.failed()).isTrue();
        assertThat(future.cause())
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Unable to find required stored request id");
    }

    @Test
    public void shouldReturnFailedFutureIfRequestBodyExceedsMaxRequestSize() {
        // given
        target = new VideoRequestFactory(2, true, videoStoredRequestProcessor, auctionRequestFactory, timeoutResolver);

        given(routingContext.getBody()).willReturn(Buffer.buffer("body"));

        // when
        final Future<?> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future.failed()).isTrue();
        assertThat(future.cause())
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Request size exceeded max size of 2 bytes.");
    }

    @Test
    public void shouldReturnFailedFutureIfRequestBodyCouldNotBeParsed() {
        // given
        given(routingContext.getBody()).willReturn(Buffer.buffer("body"));

        // when
        final Future<?> future = target.fromRequest(routingContext, 0L);

        // then
        assertThat(future.failed()).isTrue();
        assertThat(future.cause()).isInstanceOf(InvalidRequestException.class);
        assertThat(((InvalidRequestException) future.cause()).getMessages()).hasSize(1)
                .element(0).asString().startsWith("Failed to decode:");
    }

    @Test
    public void shouldReturnExpectedResultAndReturnErrors() {
        // given
        final Content content = Content.builder()
                .len(900)
                .livestream(0)
                .build();
        final Imp expectedImp1 = Imp.builder()
                .id("123_0")
                .video(Video.builder().mimes(singletonList("mime")).maxduration(100).protocols(singletonList(123)).build())
                .build();
        final Imp expectedImp2 = Imp.builder()
                .id("123_1")
                .video(Video.builder().mimes(singletonList("mime")).maxduration(100).protocols(singletonList(123)).build())
                .build();
        final ExtRequestPrebid ext = ExtRequestPrebid.builder()
                .cache(ExtRequestPrebidCache.of(null, ExtRequestPrebidCacheVastxml.of(null, null), null))
                .targeting(ExtRequestTargeting.builder()
                        .pricegranularity(Json.mapper.valueToTree(PriceGranularity.createFromString("med")))
                        .includebidderkeys(true)
                        .includebrandcategory(ExtIncludeBrandCategory.of(null, null, false))
                        .build())
                .build();
        final BidRequest bidRequest = BidRequest.builder()
                .id("bid_id")
                .imp(Arrays.asList(expectedImp1, expectedImp2))
                .user(User.builder().buyeruid("appnexus").yob(123).gender("gender").keywords("keywords").build())
                .site(Site.builder().id("siteId").content(content).build())
                .bcat(singletonList("bcat"))
                .badv(singletonList("badv"))
                .cur(singletonList("USD"))
                .tmax(0L)
                .ext(Json.mapper.valueToTree(ExtBidRequest.of(ext)))
                .build();

        final WithPodErrors<BidRequest> mergedBidRequest = WithPodErrors.of(bidRequest, singletonList(PodError.of(1, 1, singletonList("TEST"))));

        final BidRequestVideo requestVideo = BidRequestVideo.builder().build();
        given(routingContext.getBody()).willReturn(Json.encodeToBuffer(requestVideo));
        given(videoStoredRequestProcessor.processVideoRequest(any(), any(), any())).willReturn(Future.succeededFuture(mergedBidRequest));
        given(auctionRequestFactory.validateRequest(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(auctionRequestFactory.fillImplicitParameters(any(), any(), any())).willAnswer(invocation -> invocation.getArgument(0));
        given(auctionRequestFactory.toAuctionContext(any(), any(), anyLong(), any()))
                .willReturn(Future.succeededFuture());

        // when
        final Future<WithPodErrors<AuctionContext>> result = target.fromRequest(routingContext, 0L);

        // then
        verify(routingContext).getBody();
        verify(videoStoredRequestProcessor).processVideoRequest(null, emptySet(), requestVideo);
        verify(auctionRequestFactory).validateRequest(bidRequest);
        verify(auctionRequestFactory).fillImplicitParameters(bidRequest, routingContext, timeoutResolver);
        verify(auctionRequestFactory).toAuctionContext(routingContext, bidRequest, 0, timeoutResolver);

        assertThat(result.result().getPodErrors()).isEqualTo(mergedBidRequest.getPodErrors());
    }
}