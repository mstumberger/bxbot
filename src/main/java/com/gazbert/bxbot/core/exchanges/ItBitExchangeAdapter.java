/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Gareth Jon Lynch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.gazbert.bxbot.core.exchanges;

import com.gazbert.bxbot.core.api.trading.BalanceInfo;
import com.gazbert.bxbot.core.api.trading.ExchangeTimeoutException;
import com.gazbert.bxbot.core.api.trading.MarketOrder;
import com.gazbert.bxbot.core.api.trading.MarketOrderBook;
import com.gazbert.bxbot.core.api.trading.OpenOrder;
import com.gazbert.bxbot.core.api.trading.OrderType;
import com.gazbert.bxbot.core.api.trading.TradingApi;
import com.gazbert.bxbot.core.api.trading.TradingApiException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.log4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * <p>
 * <em>TODO - Remove tmp PATCH in {@link #sendAuthenticatedRequestToExchange(String, String, Map)} for occasional 401 responses sent by exchange.</em>
 * </p>
 *
 * <p>
 * Exchange Adapter for integrating with the itBit exchange.
 * The itBit API is documented <a href="https://www.itbit.com/h/api">here</a>.
 * </p>
 *
 * <p>
 * <strong>
 * DISCLAIMER:
 * This Exchange Adapter is provided as-is; it might have bugs in it and you could lose money. Despite running live
 * on itBit, it has only been unit tested up until the point of calling the
 * {@link #sendPublicRequestToExchange(String)} and {@link #sendAuthenticatedRequestToExchange(String, String, Map)}
 * methods. Use it at our own risk!
 * </strong>
 * </p>
 *
 * <p>
 * The adapter only supports the REST implementation of the <a href="https://api.itbit.com/docs">Trading API</a>.
 * </p>
 *
 * <p>The itBit exchange uses XBT for the Bitcoin currency code instead of the usual BTC. So, if you were to call
 * {@link #getBalanceInfo()}, you would need to use XBT (instead of BTC) as the key when fetching your Bitcoin balance
 * info from the returned maps.</p>
 *
 * <p>
 * The adapter also assumes that only 1 exchange account wallet has been created on the exchange. If there is more
 * than 1, it will use the first one it finds when performing the {@link #getBalanceInfo()} call.
 * </p>
 *
 * <p>
 * Exchange fees are loaded from the itbit-config.properties file on startup; they are not fetched from the exchange at
 * runtime as the itBit REST API v1 does not support this. The fees are used across all markets. Make sure you keep an
 * eye on the <a href="https://www.itbit.com/h/fees">exchange fees</a> and update the config accordingly.
 * There are different exchange fees for <a href="https://www.itbit.com/h/fees-maker-taker-model">Takers and Makers</a>
 * - this adapter will use the <em>Taker</em> fees to keep things simple for now.
 * </p>
 *
 * <p>
 * NOTE: ItBit requires all price values to be limited to 2 decimal places and amount values to be limited to 4 decimal
 * places when creating orders. This adapter truncates any prices with more than 2 decimal places and rounds using
 * {@link java.math.RoundingMode#HALF_EVEN}, E.g. 250.176 would be sent to the exchange as 250.18. The same is done for
 * the order amount, but to 4 decimal places.
 * </p>
 *
 * <p>
 * The Exchange Adapter is <em>not</em> thread safe. It expects to be called using a single thread in order to
 * preserve trade execution order. The {@link URLConnection} achieves this by blocking/waiting on the input stream
 * (response) for each API call.
 * </p>
 *
 * <p>
 * The {@link TradingApi} calls will throw a {@link ExchangeTimeoutException} if a network error occurs trying to
 * connect to the exchange. A {@link TradingApiException} is thrown for <em>all</em> other failures.
 * </p>
 *
 * @author gazbert
 */
public final class ItBitExchangeAdapter implements TradingApi {

    private static final Logger LOG = Logger.getLogger(ItBitExchangeAdapter.class);

    /**
     * The version of the itBit API being used.
     */
    private static final String ITBIT_API_VERSION = "v1";

    /**
     * The public API URI.
     * The itBit Production Host is: https://api.itbit.com/v1/
     */
    private static final String PUBLIC_API_BASE_URL = "https://api.itbit.com/" + ITBIT_API_VERSION + "/";

    /**
     * The Authenticated API URI - it is the same as the Authenticated URL as of 25 Sep 2015.
     */
    private static final String AUTHENTICATED_API_URL = PUBLIC_API_BASE_URL;

    /**
     * Used for reporting unexpected errors.
     */
    private static final String UNEXPECTED_ERROR_MSG = "Unexpected error has occurred in itBit Exchange Adapter. ";

    /**
     * Unexpected IO error message for logging.
     */
    private static final String UNEXPECTED_IO_ERROR_MSG = "Failed to connect to Exchange due to unexpected IO error.";

    /**
     * IO 50x Timeout error message for logging.
     */
    private static String IO_50X_TIMEOUT_ERROR_MSG = "Failed to connect to Exchange due to 50x timeout.";

    /**
     * IO Socket Timeout error message for logging.
     */
    private static final String IO_SOCKET_TIMEOUT_ERROR_MSG = "Failed to connect to Exchange due to socket timeout.";

    /**
     * Bad Request error message for logging.
     */
    private static final String BAD_REQUEST_ERROR_MSG =  "Exchange has rejected request due to bad data being sent to it.";

    /**
     * Used for building error messages for missing config.
     */
    private static final String CONFIG_IS_NULL_OR_ZERO_LENGTH = " cannot be null or zero length! HINT: is the value set in the ";

    /**
     * Your itBit API keys and connection timeout config.
     * This file must be on BX-bot's runtime classpath located at: ./resources/itbit/itbit-config.properties
     */
    private static final String CONFIG_FILE = "itbit/itbit-config.properties";

    /**
     * Name of client id property in config file.
     */
    private static final String USER_ID_PROPERTY_NAME = "userId";

    /**
     * Name of PUBLIC key property in config file.
     */
    private static final String KEY_PROPERTY_NAME = "key";

    /**
     * Name of secret property in config file.
     */
    private static final String SECRET_PROPERTY_NAME = "secret";

    /**
     * Name of buy fee property in config file.
     */
    private static final String BUY_FEE_PROPERTY_NAME = "buy-fee";

    /**
     * Name of sell fee property in config file.
     */
    private static final String SELL_FEE_PROPERTY_NAME = "sell-fee";

    /**
     * Name of connection timeout property in config file.
     */
    private static final String CONNECTION_TIMEOUT_PROPERTY_NAME = "connection-timeout";

    /**
     * Nonce used for sending authenticated messages to the exchange.
     */
    private static long nonce = 0;

    /**
     * The UUID of the wallet in use on the exchange.
     */
    private String walletId;

    /**
     * The connection timeout in SECONDS for terminating hung connections to the exchange.
     */
    private int connectionTimeout;

    /**
     * Exchange buy fees in % in {@link BigDecimal} format.
     */
    private BigDecimal buyFeePercentage;

    /**
     * Exchange sell fees in % in {@link BigDecimal} format.
     */
    private BigDecimal sellFeePercentage;

    /**
     * Used to indicate if we have initialised the MAC authentication protocol.
     */
    private boolean initializedMACAuthentication = false;

    /**
     * The user id.
     */
    private String userId = "";

    /**
     * The key used in the MAC message.
     */
    private String key = "";

    /**
     * The secret used for signing MAC message.
     */
    private String secret = "";

    /**
     * Provides the "Message Authentication Code" (MAC) algorithm used for the secure messaging layer.
     * Used to encrypt the hash of the entire message with the private key to ensure message integrity.
     */
    private Mac mac;

    /**
     * GSON engine used for parsing JSON in itBit API call responses.
     */
    private Gson gson;


    /**
     * Constructor initialises the Exchange Adapter for using the itBit API.
     */
    public ItBitExchangeAdapter() {

        // set the initial nonce used in the secure messaging.
        nonce = System.currentTimeMillis() / 1000;

        loadConfig();
        initSecureMessageLayer();
        initGson();
    }

    // ------------------------------------------------------------------------------------------------
    // itBit REST Trade API Calls adapted to the Trading API.
    // See https://api.itbit.com/docs
    // ------------------------------------------------------------------------------------------------

    @Override
    public String createOrder(String marketId, OrderType orderType, BigDecimal quantity, BigDecimal price) throws
            TradingApiException, ExchangeTimeoutException {

        try {

            if (walletId == null) {
                // need to fetch walletId if first API call
                getBalanceInfo();
            }

            final Map<String, String> params = getRequestParamMap();
            params.put("type", "limit");

            // note we need to limit amount to 4 decimal places else exchange will barf
            params.put("amount", new DecimalFormat("#.####").format(quantity));

            // Display param seems to be optional as per the itBit sample code:
            // https://github.com/itbit/itbit-restapi-python/blob/master/itbit_api.py - def create_order
            // params.put("display", new DecimalFormat("#.####").format(quantity)); // use the same as amount

            // note we need to limit price to 2 decimal places else exchange will barf
            params.put("price", new DecimalFormat("#.##").format(price));

            params.put("instrument", marketId);

            // This param is unique for itBit - no other Exchange Adapter I've coded requires this :-/
            // A bit hacky below, but I'm not tweaking the Trading API createOrder() call just for itBit.
            params.put("currency", marketId.substring(0,3));

            if (orderType == OrderType.BUY) {
                params.put("side", "buy");
            } else if (orderType == OrderType.SELL) {
                params.put("side", "sell");
            } else {
                final String errorMsg = "Invalid order type: " + orderType
                        + " - Can only be "
                        + OrderType.BUY.getStringValue() + " or "
                        + OrderType.SELL.getStringValue();
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            // Adapter does not using optional clientOrderIdentifier and clientOrderIdentifier params.
            // params.put("metadata", "{}");
            // params.put("clientOrderIdentifier", "id_123");

            final ItBitHttpResponse response = sendAuthenticatedRequestToExchange(
                    "POST", "wallets/" + walletId + "/orders", params);

            if (LOG.isDebugEnabled()) {
                LOG.debug("createOrder() response: " + response);
            }

            if (response.statusCode == HttpURLConnection.HTTP_CREATED) {
                final ItBitNewOrderResponse itBitNewOrderResponse = gson.fromJson(response.getPayload(),
                        ItBitNewOrderResponse.class);
                return itBitNewOrderResponse.id;
            } else {
                final String errorMsg = "Failed to create order on exchange. Details: " + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    /*
     * marketId is not needed for cancelling orders on this exchange.
     */
    @Override
    public boolean cancelOrder(String orderId, String marketIdNotNeeded) throws TradingApiException, ExchangeTimeoutException {

        try {

            if (walletId == null) {
                // need to fetch walletId if first API call
                getBalanceInfo();
            }

            final ItBitHttpResponse response = sendAuthenticatedRequestToExchange(
                    "DELETE", "wallets/" + walletId + "/orders/" + orderId, null);

            if (LOG.isDebugEnabled()) {
                LOG.debug("cancelOrder() response: " + response);
            }

            if (response.statusCode == HttpURLConnection.HTTP_ACCEPTED) {
                gson.fromJson(response.getPayload(), ItBitCancelOrderResponse.class);
                return true;
            } else {
                final String errorMsg = "Failed to cancel order on exchange. Details: " + response;
                LOG.error(errorMsg);
                return false;
            }

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public List<OpenOrder> getYourOpenOrders(String marketId) throws TradingApiException, ExchangeTimeoutException {

        try {

            if (walletId == null) {
                // need to fetch walletId if first API call
                getBalanceInfo();
            }

            final Map<String, String> params = getRequestParamMap();
            params.put("status", "open"); // we only want open orders

            final ItBitHttpResponse response = sendAuthenticatedRequestToExchange(
                    "GET", "wallets/" + walletId + "/orders", params);

            if (LOG.isDebugEnabled()) {
                LOG.debug("getYourOpenOrders() response: " + response);
            }

            if (response.statusCode == HttpURLConnection.HTTP_OK) {

                final ItBitYourOrder[] itBitOpenOrders = gson.fromJson(response.getPayload(), ItBitYourOrder[].class);

                // adapt
                final List<OpenOrder> ordersToReturn = new ArrayList<>();
                for (final ItBitYourOrder itBitOpenOrder : itBitOpenOrders) {
                    OrderType orderType;
                    switch (itBitOpenOrder.side) {
                        case "buy":
                            orderType = OrderType.BUY;
                            break;
                        case "sell":
                            orderType = OrderType.SELL;
                            break;
                        default:
                            throw new TradingApiException(
                                    "Unrecognised order type received in getYourOpenOrders(). Value: " + itBitOpenOrder.side);
                    }

                    final OpenOrder order = new OpenOrder(
                            itBitOpenOrder.id,
                            Date.from(Instant.parse(itBitOpenOrder.createdTime)), // format: 2015-10-01T18:10:39.3930000Z
                            marketId,
                            orderType,
                            itBitOpenOrder.price,
                            itBitOpenOrder.amount.subtract(itBitOpenOrder.amountFilled), // remaining - not provided by itBit
                            itBitOpenOrder.amount,
                            itBitOpenOrder.price.multiply(itBitOpenOrder.amount)); // total - not provided by itBit

                    ordersToReturn.add(order);
                }
                return ordersToReturn;
            } else {
                final String errorMsg = "Failed to get your open orders from exchange. Details: " + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public MarketOrderBook getMarketOrders(String marketId) throws TradingApiException, ExchangeTimeoutException {

        try {
            final ItBitHttpResponse response = sendPublicRequestToExchange("/markets/" + marketId + "/order_book");

            if (LOG.isDebugEnabled()) {
                LOG.debug("getMarketOrders() response: " + response);
            }

            if (response.statusCode == HttpURLConnection.HTTP_OK) {

                final ItBitOrderBookWrapper orderBook = gson.fromJson(response.getPayload(), ItBitOrderBookWrapper.class);

                // adapt BUYs
                final List<MarketOrder> buyOrders = new ArrayList<>();
                for (ItBitMarketOrder itBitBuyOrder : orderBook.bids) {
                    final MarketOrder buyOrder = new MarketOrder(
                            OrderType.BUY,
                            itBitBuyOrder.get(0),
                            itBitBuyOrder.get(1),
                            itBitBuyOrder.get(0).multiply(itBitBuyOrder.get(1)));
                    buyOrders.add(buyOrder);
                }

                // adapt SELLs
                final List<MarketOrder> sellOrders = new ArrayList<>();
                for (ItBitMarketOrder itBitSellOrder : orderBook.asks) {
                    final MarketOrder sellOrder = new MarketOrder(
                            OrderType.SELL,
                            itBitSellOrder.get(0),
                            itBitSellOrder.get(1),
                            itBitSellOrder.get(0).multiply(itBitSellOrder.get(1)));
                    sellOrders.add(sellOrder);
                }

                return new MarketOrderBook(marketId, sellOrders, buyOrders);
            } else {
                final String errorMsg = "Failed to get market order book from exchange. Details: " + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public BigDecimal getLatestMarketPrice(String marketId) throws TradingApiException, ExchangeTimeoutException {

        try {

            final ItBitHttpResponse response = sendPublicRequestToExchange("/markets/" + marketId + "/ticker");

            if (LOG.isDebugEnabled()) {
                LOG.debug("getLatestMarketPrice() response: " + response);
            }

            if (response.statusCode == HttpURLConnection.HTTP_OK) {

                final ItBitTicker itBitTicker = gson.fromJson(response.getPayload(), ItBitTicker.class);
                return itBitTicker.lastPrice;
            } else {
                final String errorMsg = "Failed to get market ticker from exchange. Details: " + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public BalanceInfo getBalanceInfo() throws TradingApiException, ExchangeTimeoutException {

        try {

            final Map<String, String> params = getRequestParamMap();
            params.put("userId", userId);

            final ItBitHttpResponse response = sendAuthenticatedRequestToExchange("GET", "wallets", params);

            if (LOG.isDebugEnabled()) {
                LOG.debug("getBalanceInfo() response: " + response);
            }

            if (response.statusCode == HttpURLConnection.HTTP_OK) {

                final ItBitWallet[] itBitWallets = gson.fromJson(response.getPayload(), ItBitWallet[].class);

                // assume only 1 trading account wallet being used on exchange
                final ItBitWallet exchangeWallet = itBitWallets[0];

                /*
                 * If this is the first time to fetch the balance/wallet info, store the wallet UUID for future calls.
                 * The Trading Engine will always call this method first, before any user Trading Strategies are invoked,
                 * so any of the other Trading API methods that rely on the wallet UUID will be satisfied.
                 */
                if (walletId == null) {
                    walletId = exchangeWallet.id;
                }

                // adapt
                final Map<String, BigDecimal> balancesAvailable = new HashMap<>();
                final List<ItBitBalance> balances = exchangeWallet.balances;
                for (final ItBitBalance balance : balances) {
                    balancesAvailable.put(balance.currency, balance.availableBalance);
                }

                // 2nd arg of BalanceInfo constructor for reserved/on-hold balances is not provided by exchange.
                return new BalanceInfo(balancesAvailable, new HashMap<>());
            } else {
                final String errorMsg = "Failed to get your wallet balance info from exchange. Details: " + response;
                LOG.error(errorMsg);
                throw new TradingApiException(errorMsg);
            }

        } catch (ExchangeTimeoutException | TradingApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.error(UNEXPECTED_ERROR_MSG, e);
            throw new TradingApiException(UNEXPECTED_ERROR_MSG, e);
        }
    }

    @Override
    public BigDecimal getPercentageOfBuyOrderTakenForExchangeFee(String marketId) throws TradingApiException,
            ExchangeTimeoutException {

        // itBit does not provide API call for fetching % buy fee; it only provides the fee monetary value for a
        // given order via /wallets/{walletId}/trades API call. We load the % fee statically from itbit-config.properties
        return buyFeePercentage;
    }

    @Override
    public BigDecimal getPercentageOfSellOrderTakenForExchangeFee(String marketId) throws TradingApiException,
            ExchangeTimeoutException {

        // itBit does not provide API call for fetching % sell fee; it only provides the fee monetary value for a
        // given order via/wallets/{walletId}/trades API call. We load the % fee statically from itbit-config.properties
        return sellFeePercentage;
    }

    @Override
    public String getImplName() {
        return "itBit REST API v1";
    }

    // ------------------------------------------------------------------------------------------------
    //  GSON classes for JSON responses.
    //  See https://api.itbit.com/docs
    // ------------------------------------------------------------------------------------------------

    /**
     * <p>
     * GSON class for holding itBit order returned from:
     * "Cancel Order" /wallets/{walletId}/orders/{orderId} API call.
     * </p>
     *
     * <p>
     * No payload returned by exchange on success.
     * </p>
     *
     * @author gazbert
     */
    private static class ItBitCancelOrderResponse {
    }

    /**
     * <p>
     * GSON class for holding itBit new order response from:
     * "Create New Order" POST /wallets/{walletId}/orders API call.
     * </p>
     *
     * <p>
     * It is exactly the same as order returned in Get Orders response.
     * </p>
     *
     * @author gazbert
     */
    private static class ItBitNewOrderResponse extends ItBitYourOrder {
    }

    /**
     * GSON class for holding itBit order returned from:
     * "Get Orders" /wallets/{walletId}/orders{?instrument,page,perPage,status} API call.
     *
     * @author gazbert
     */
    private static class ItBitYourOrder {

        public String id;
        public String walletId;
        public String side; // 'buy' or 'sell'
        public String instrument; // the marketId e.g. 'XBTUSD'
        public String type; // order type e.g. "limit"
        public BigDecimal amount; // the original amount
        public BigDecimal displayAmount; // ??? not documented in the REST API
        public BigDecimal price;
        public BigDecimal volumeWeightedAveragePrice;
        public BigDecimal amountFilled;
        public String createdTime; // e.g. "2015-10-01T18:10:39.3930000Z"
        public String status; // e.g. "open"
        public ItBitOrderMetadata metadata; // {} value returned - no idea what this is
        public String clientOrderIdentifier; // cool - broker support :-)

        @Override
        public String toString() {
            return this.getClass().getSimpleName()
                    + " ["
                    + "id=" + id
                    + ", walletId=" + walletId
                    + ", side=" + side
                    + ", instrument=" + instrument
                    + ", type=" + type
                    + ", amount=" + amount
                    + ", displayAmount=" + displayAmount
                    + ", price=" + price
                    + ", volumeWeightedAveragePrice=" + volumeWeightedAveragePrice
                    + ", amountFilled=" + amountFilled
                    + ", createdTime=" + createdTime
                    + ", status=" + status
                    + ", metadata=" + metadata
                    + ", clientOrderIdentifier=" + clientOrderIdentifier
                    + "]";
        }
    }

    /**
     * GSON class for holding Your Order metadata. No idea what this is / or gonna be...
     *
     * @author gazbert
     */
    private static class ItBitOrderMetadata {
    }

    /**
     * GSON class for holding itBit ticker returned from:
     * "Get Order Book" /markets/{tickerSymbol}/order_book API call.
     *
     * @author gazbert
     */
    private static class ItBitOrderBookWrapper {

        public List<ItBitMarketOrder> bids;
        public List<ItBitMarketOrder> asks;

        @Override
        public String toString() {
            return ItBitOrderBookWrapper.class.getSimpleName()
                    + " ["
                    + "bids=" + bids
                    + ", asks=" + asks
                    + "]";
        }
    }

    /**
     * GSON class for holding Market Orders. First element in array is price, second element is amount.
     *
     * @author gazbert
     */
    private static class ItBitMarketOrder extends ArrayList<BigDecimal> {
        private static final long serialVersionUID = -4959711260747077759L;
    }

    /**
     * GSON class for holding itBit ticker returned from:
     * "Get Ticker" /markets/{tickerSymbol}/ticker API call.
     *
     * @author gazbert
     */
    private static class ItBitTicker {

        // field names map to the JSON arg names
        public String pair; // e.g. XBTUSD
        public BigDecimal bid;
        public BigDecimal bidAmt;
        public BigDecimal ask;
        public BigDecimal askAmt;
        public BigDecimal lastPrice; // we only wants this precious
        public BigDecimal lastAmt;
        public BigDecimal lastvolume24hAmt;
        public BigDecimal volumeToday;
        public BigDecimal high24h;
        public BigDecimal low24h;
        public BigDecimal highToday;
        public BigDecimal lowToday;
        public BigDecimal openToday;
        public BigDecimal vwapToday;
        public BigDecimal vwap24h;
        public String serverTimeUTC;

        @Override
        public String toString() {
            return ItBitTicker.class.getSimpleName()
                    + " ["
                    + "pair=" + pair
                    + ", bid=" + bid
                    + ", bidAmt=" + bidAmt
                    + ", ask=" + ask
                    + ", askAmt=" + askAmt
                    + ", lastPrice=" + lastPrice
                    + ", lastAmt=" + lastAmt
                    + ", lastvolume24hAmt=" + lastvolume24hAmt
                    + ", volumeToday=" + volumeToday
                    + ", high24h=" + high24h
                    + ", low24h=" + low24h
                    + ", highToday=" + highToday
                    + ", lowToday=" + lowToday
                    + ", openToday=" + openToday
                    + ", vwapToday=" + vwapToday
                    + ", vwap24h=" + vwap24h
                    + ", serverTimeUTC=" + serverTimeUTC
                    + "]";
        }
    }

    /**
     * GSON class for holding itBit wallets returned from:
     * "Get All Wallets" /wallets{?userId,page,perPage} API call.
     *
     * @author gazbert
     */
    private static class ItBitWallet {

        public String id;
        public String userId;
        public String name;
        public List<ItBitBalance> balances;

        @Override
        public String toString() {
            return ItBitWallet.class.getSimpleName()
                    + " ["
                    + "id=" + id
                    + ", userId=" + userId
                    + ", name=" + name
                    + ", balances=" + balances
                    + "]";
        }
    }

    /**
     * GSON class for holding itBit wallet balances.
     *
     * @author gazbert
     */
    private static class ItBitBalance {

        public BigDecimal availableBalance;
        public BigDecimal totalBalance;
        public String currency; // e.g. USD

        @Override
        public String toString() {
            return ItBitBalance.class.getSimpleName()
                    + " ["
                    + "availableBalance=" + availableBalance
                    + ", totalBalance=" + totalBalance
                    + ", currency=" + currency
                    + "]";
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  Transport layer
    // ------------------------------------------------------------------------------------------------

    /**
     * Wrapper class for holding itBit HTTP responses.
     *
     * Package private for unit testing ;-o
     *
     * @author gazbert
     */
    static class ItBitHttpResponse {

        private int statusCode;
        private String reasonPhrase;
        private String payload;

        public ItBitHttpResponse(int statusCode, String reasonPhrase, String payload) {
            this.statusCode = statusCode;
            this.reasonPhrase = reasonPhrase;
            this.payload = payload;
        }

        public String getPayload() {
            return payload;
        }

        @Override
        public String toString() {
            return ItBitHttpResponse.class.getSimpleName()
                    + " ["
                    + "statusCode=" + statusCode
                    + ", reasonPhrase=" + reasonPhrase
                    + ", payload=" + payload
                    + "]";
        }
    }

    /**
     * Makes a public API call to itBit exchange. Uses HTTP GET.
     *
     * @param apiMethod the API method to call.
     * @return the response from the exchange.
     * @throws ExchangeTimeoutException if there is a network issue connecting to exchange.
     * @throws TradingApiException if anything unexpected happens.
     */
    private ItBitHttpResponse sendPublicRequestToExchange(String apiMethod) throws ExchangeTimeoutException, TradingApiException {

        HttpURLConnection exchangeConnection = null;
        final StringBuilder exchangeResponse = new StringBuilder();

        try {

            final URL url = new URL(PUBLIC_API_BASE_URL + apiMethod);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Using following URL for API call: " + url);
            }

            exchangeConnection = (HttpURLConnection) url.openConnection();
            exchangeConnection.setUseCaches(false);
            exchangeConnection.setDoOutput(true);

            // no JSON this time
            exchangeConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            // Er, perhaps, I need to be a bit more stealth here...
            exchangeConnection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/35.0.1916.114 Safari/537.36");

            /*
             * Add a timeout so we don't get blocked indefinitley; timeout on URLConnection is in millis.
             * connectionTimeout is in SECONDS and comes from itbit-config.properties config.
             */
            final int timeoutInMillis = connectionTimeout * 1000;
            exchangeConnection.setConnectTimeout(timeoutInMillis);
            exchangeConnection.setReadTimeout(timeoutInMillis);

            // Grab the response - we just block here as per Connection API
            final BufferedReader responseInputStream = new BufferedReader(new InputStreamReader(
                    exchangeConnection.getInputStream()));

            // Read the JSON response lines into our response buffer
            String responseLine;
            while ((responseLine = responseInputStream.readLine()) != null) {
                exchangeResponse.append(responseLine);
            }
            responseInputStream.close();

            return new ItBitHttpResponse(exchangeConnection.getResponseCode(), exchangeConnection.getResponseMessage(),
                    exchangeResponse.toString());

        } catch (MalformedURLException e) {
            final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new TradingApiException(errorMsg, e);

        } catch (SocketTimeoutException e) {
            final String errorMsg = IO_SOCKET_TIMEOUT_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new ExchangeTimeoutException(errorMsg, e);

        } catch (IOException e) {

            try {

                /*
                 * Exchange sometimes fails with these codes, but recovers by next request...
                 */
                if (exchangeConnection != null && (exchangeConnection.getResponseCode() == 502
                        || exchangeConnection.getResponseCode() == 503
                        || exchangeConnection.getResponseCode() == 504)) {

                    final String errorMsg = IO_50X_TIMEOUT_ERROR_MSG;
                    LOG.error(errorMsg, e);
                    throw new ExchangeTimeoutException(errorMsg, e);

                /*
                 * Check for itBit specific REST error responses so we can return useful data to caller.
                 */
                } else if (exchangeConnection != null && (exchangeConnection.getResponseCode() == 401
                                || exchangeConnection.getResponseCode() == 404
                                || exchangeConnection.getResponseCode() == 422)) {

                        final String errorMsg = BAD_REQUEST_ERROR_MSG;
                        LOG.error(errorMsg, e);
                        return new ItBitHttpResponse(exchangeConnection.getResponseCode(),
                                exchangeConnection.getResponseMessage(), exchangeResponse.toString());

                } else {
                    final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
                    LOG.error(errorMsg, e);
                    e.printStackTrace();
                    throw new TradingApiException(errorMsg, e);
                }
            } catch (IOException e1) {

                final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
                LOG.error(errorMsg, e1);
                throw new TradingApiException(errorMsg, e1);
            }
        } finally {
            if (exchangeConnection != null) {
                exchangeConnection.disconnect();
            }
        }
    }

    /**
     * <p>
     * Makes Authenticated API call to itBit exchange.
     * </p>
     *
     * <p>
     * Quite complex, but well documented
     * <a href="https://api.itbit.com/docs#faq-2.-how-do-i-sign-a-request?">here.</a>
     * </p>
     *
     * @param httpMethod the HTTP method to use, e.g. GET, POST, DELETE
     * @param apiMethod the API method to call.
     * @param params the query param args to use in the API call.
     * @return the response from the exchange.
     * @throws ExchangeTimeoutException if there is a network issue connecting to exchange.
     * @throws TradingApiException if anything unexpected happens.
     */
    private ItBitHttpResponse sendAuthenticatedRequestToExchange(String httpMethod, String apiMethod, Map<String, String> params)
            throws ExchangeTimeoutException, TradingApiException {

        if (!initializedMACAuthentication) {
            final String errorMsg = "MAC Message security layer has not been initialized.";
            LOG.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        HttpURLConnection exchangeConnection = null;
        final StringBuilder exchangeResponse = new StringBuilder();

        try {

            // Generate new UNIX time in secs
            final String unixTime = Long.toString(System.currentTimeMillis());

            // increment nonce for use in this call
            nonce++;

            if (params == null) {
                // create empty map for non-param API calls
                params = new HashMap<>();
            }

            /*
             * Construct an array of UTF-8 encoded strings. That array should contain, in order,
             * the http verb of the request being signed (e.g. “GET”), the full url of the request,
             * the body of the message being sent, the nonce as a string, and the timestamp as a string.
             * If the request has no body, an empty string should be used.
             */
            final String invocationUrl;
            String requestBody = "";
            String requestBodyForSignature = "";
            final List<String> signatureParamList = new ArrayList<>();
            signatureParamList.add(httpMethod);

            switch (httpMethod) {
                case "GET" :
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Building secure GET request...");
                    }

                    // Build (optional) query param string
                    final StringBuilder queryParamBuilder = new StringBuilder();
                    for (final String param : params.keySet()) {
                        if (queryParamBuilder.length() > 0) {
                            queryParamBuilder.append("&");
                        }
                        //noinspection deprecation
                        // Don't URL encode as it messed up the UUID params, e.g. wallet id
                        //queryParams += param + "=" + URLEncoder.encode(params.get(param));
                        queryParamBuilder.append(param).append("=").append(params.get(param));
                    }

                    final String queryParams = queryParamBuilder.toString();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Query param string: " + queryParams);
                    }

                    if (params.isEmpty()) {
                        invocationUrl = AUTHENTICATED_API_URL + apiMethod;
                        signatureParamList.add(invocationUrl);
                    } else {
                        invocationUrl = AUTHENTICATED_API_URL + apiMethod + "?" + queryParams;
                        signatureParamList.add(invocationUrl);
                    }

                    signatureParamList.add(requestBodyForSignature); // request body is empty JSON string for a GET
                    break;

                case "POST" :
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Building secure POST request...");
                    }

                    invocationUrl = AUTHENTICATED_API_URL + apiMethod;
                    signatureParamList.add(invocationUrl);

                    requestBody = gson.toJson(params);
                    signatureParamList.add(requestBody);
                    break;

                case "DELETE" :
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Building secure DELETE request...");
                    }

                    invocationUrl = AUTHENTICATED_API_URL + apiMethod;
                    signatureParamList.add(invocationUrl);
                    signatureParamList.add(requestBodyForSignature); // request body is empty JSON string for a DELETE
                    break;

                default:
                    throw new IllegalArgumentException("Don't know how to build secure [" + httpMethod + "] request!");
            }

            // Add the nonce
            signatureParamList.add(Long.toString(nonce));

            // Add the UNIX time
            signatureParamList.add(unixTime);

            /*
             * Convert that array to JSON, encoded as UTF-8. The resulting JSON should contain no spaces or other
             * whitespace characters. For example, a valid JSON-encoded array might look like:
             * '["GET","https://api.itbit.com/v1/wallets/7e037345-1288-4c39-12fe-d0f99a475a98","","5","1405385860202"]'
             */
            final String signatureParamsInJson = gson.toJson(signatureParamList);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Signature params in JSON: " + signatureParamsInJson);
            }

            // Prepend the string version of the nonce to the JSON-encoded array string
            final String noncePrependedToJson = Long.toString(nonce) + signatureParamsInJson;
            if (LOG.isDebugEnabled()) {
                LOG.debug("Nonce prepended to Signature params in JSON: " + noncePrependedToJson);
            }

            // Construct the SHA-256 hash of the noncePrependedToJson. Call this the message hash.
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(noncePrependedToJson.getBytes());
            final BigInteger messageHash = new BigInteger(md.digest());
            if (LOG.isDebugEnabled()) {
                LOG.debug("Message SHA-256 Hash: " + messageHash);
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Invocation URL in SHA-512 HMAC: " + invocationUrl);
            }

            // Prepend the UTF-8 encoded request URL to the message hash.
            // Generate the SHA-512 HMAC of the prependRequestUrlToMsgHash using your API secret as the key.
            mac.reset(); // force reset
            mac.update(invocationUrl.getBytes());
            mac.update(messageHash.toByteArray());

            final String signature = DatatypeConverter.printBase64Binary((new BigInteger(mac.doFinal())).toByteArray());
            if (LOG.isDebugEnabled()) {
                LOG.debug("Signature in Base64: " + signature);
            }

            final URL url = new URL(invocationUrl);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Using following URL for API call: " + url);
            }

            exchangeConnection = (HttpURLConnection) url.openConnection();
            exchangeConnection.setUseCaches(false);
            exchangeConnection.setDoOutput(true);
            exchangeConnection.setRequestMethod(httpMethod); // GET|POST|DELETE

            // Add Authorization header
            // Generate the authorization header by concatenating the client key with a colon separator (‘:’)
            // and the signature. The resulting string should look like "clientkey:signature".
            exchangeConnection.setRequestProperty("Authorization", key + ":" + signature);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Authorization: " + key + ":" + signature);
            }

            // Add timestamp header
            exchangeConnection.setRequestProperty("X-Auth-Timestamp", unixTime);
            if (LOG.isDebugEnabled()) {
                LOG.debug("X-Auth-Timestamp: " + unixTime);
            }

            // Add nonce header
            exchangeConnection.setRequestProperty("X-Auth-Nonce", Long.toString(nonce));
            if (LOG.isDebugEnabled()) {
                LOG.debug("X-Auth-Nonce: " + Long.toString(nonce));
            }

            // Payload is JSON for this exchange
            exchangeConnection.setRequestProperty("Content-Type", "application/json");

            // Er, perhaps, I need to be a bit more stealth here...
            exchangeConnection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/35.0.1916.114 Safari/537.36");

            /*
             * Add a timeout so we don't get blocked indefinitley; timeout on URLConnection is in millis.
             * connectionTimeout is in SECONDS and comes from itbit-config.properties config.
             */
            final int timeoutInMillis = connectionTimeout * 1000;
            exchangeConnection.setConnectTimeout(timeoutInMillis);
            exchangeConnection.setReadTimeout(timeoutInMillis);

            if (httpMethod.equalsIgnoreCase("POST")) {

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Doing POST with request body: " + requestBody);
                }

                final OutputStreamWriter outputPostStream = new OutputStreamWriter(exchangeConnection.getOutputStream());
                outputPostStream.write(requestBody);
                outputPostStream.close();
            }

            // Grab the response - we just block here as per Connection API
            final BufferedReader responseInputStream = new BufferedReader(new InputStreamReader(
                    exchangeConnection.getInputStream()));

            // Read the JSON response lines into our response buffer
            String responseLine;
            while ((responseLine = responseInputStream.readLine()) != null) {
                exchangeResponse.append(responseLine);
            }
            responseInputStream.close();

            return new ItBitHttpResponse(exchangeConnection.getResponseCode(), exchangeConnection.getResponseMessage(),
                    exchangeResponse.toString());

        } catch (MalformedURLException e) {
            final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new TradingApiException(errorMsg, e);

        } catch (SocketTimeoutException e) {
            final String errorMsg = IO_SOCKET_TIMEOUT_ERROR_MSG;
            LOG.error(errorMsg, e);
            throw new ExchangeTimeoutException(errorMsg, e);

        } catch (IOException e) {

            try {

                /*
                 * TODO - remove this tmp PATCH when ItBit fix their side or I find the bug in my code... ;-)
                 * Patch to catch the 401s that ItBit occasionally sends... approx 1-2 per hour.
                 * In discussion with exchange.
                 */
                if (exchangeConnection != null && (exchangeConnection.getResponseCode() == 401)) {

                    final String errorMsg = "Received rogue ItBit 401 response again... :-/";
                    LOG.error(errorMsg, e);
                    throw new ExchangeTimeoutException(errorMsg, e);

                /*
                 * Exchange sometimes fails with these codes, but recovers by next request...
                 */
                } else if (exchangeConnection != null && (exchangeConnection.getResponseCode() == 502
                        || exchangeConnection.getResponseCode() == 503
                        || exchangeConnection.getResponseCode() == 504)) {

                    final String errorMsg = IO_50X_TIMEOUT_ERROR_MSG;
                    LOG.error(errorMsg, e);
                    throw new ExchangeTimeoutException(errorMsg, e);

                /*
                 * Check for itBit specific REST error responses so we can return useful data to caller.
                 */
                } else if (exchangeConnection != null && (exchangeConnection.getResponseCode() == 401
                                || exchangeConnection.getResponseCode() == 404
                                || exchangeConnection.getResponseCode() == 422)) {

                    final String errorMsg = BAD_REQUEST_ERROR_MSG;
                    LOG.error(errorMsg, e);
                    return new ItBitHttpResponse(exchangeConnection.getResponseCode(),
                            exchangeConnection.getResponseMessage(), exchangeResponse.toString());

                } else {
                    final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
                    LOG.error(errorMsg, e);
                    e.printStackTrace();
                    throw new TradingApiException(errorMsg, e);
                }
            } catch (IOException e1) {

                final String errorMsg = UNEXPECTED_IO_ERROR_MSG;
                LOG.error(errorMsg, e1);
                throw new TradingApiException(errorMsg, e1);
            }

        } catch (NoSuchAlgorithmException e) {
            final String errorMsg = "Failed to create SHA-256 digest when building message signature.";
            LOG.error(errorMsg, e);
            throw new TradingApiException(errorMsg, e);

        } finally {
            if (exchangeConnection != null) {
                exchangeConnection.disconnect();
            }
        }
    }

    /**
     * Initialises the secure messaging layer
     * Sets up the MAC to safeguard the data we send to the exchange.
     * We fail hard n fast if any of this stuff blows.
     */
    private void initSecureMessageLayer() {

        // Setup the MAC
        try {
            final SecretKeySpec keyspec = new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA512");
            mac = Mac.getInstance("HmacSHA512");
            mac.init(keyspec);
            initializedMACAuthentication = true;
        } catch (UnsupportedEncodingException | NoSuchAlgorithmException e) {
            final String errorMsg = "Failed to setup MAC security. HINT: Is HMAC-SHA512 installed?";
            LOG.error(errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        } catch (InvalidKeyException e) {
            final String errorMsg = "Failed to setup MAC security. Secret key seems invalid!";
            LOG.error(errorMsg, e);
            throw new IllegalArgumentException(errorMsg, e);
        }
    }
    // ------------------------------------------------------------------------------------------------
    //  Config methods
    // ------------------------------------------------------------------------------------------------

    /**
     * Loads Exchange Adapter config.
     */
    private void loadConfig() {

        final String configFile = getConfigFileLocation();
        final Properties configEntries = new Properties();
        final InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(configFile);

        if (inputStream == null) {
            final String errorMsg = "Cannot find itBit config at: " + configFile + " HINT: is it on BX-bot's classpath?";
            LOG.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        try {
            configEntries.load(inputStream);

            /*
             * Grab the user id
             */
            userId = configEntries.getProperty(USER_ID_PROPERTY_NAME);

            // WARNING: careful when you log this
//            if (LOG.isInfoEnabled()) {
//                LOG.info(USER_ID_PROPERTY_NAME + ": " + userId);
//            }

            if (userId == null || userId.length() == 0) {
                final String errorMsg = USER_ID_PROPERTY_NAME + CONFIG_IS_NULL_OR_ZERO_LENGTH + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            /*
             * Grab the public key
             */
            key = configEntries.getProperty(KEY_PROPERTY_NAME);

            // WARNING: careful when you log this
//            if (LOG.isInfoEnabled()) {
//                LOG.info(KEY_PROPERTY_NAME + ": " + key);
//            }

            if (key == null || key.length() == 0) {
                final String errorMsg = KEY_PROPERTY_NAME + CONFIG_IS_NULL_OR_ZERO_LENGTH + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            /*
             * Grab the private key
             */
            secret = configEntries.getProperty(SECRET_PROPERTY_NAME);

            // WARNING: careful when you log this
//            if (LOG.isInfoEnabled()) {
//                LOG.info(SECRET_PROPERTY_NAME + ": " + secret);
//            }

            if (secret == null || secret.length() == 0) {
                final String errorMsg = SECRET_PROPERTY_NAME + CONFIG_IS_NULL_OR_ZERO_LENGTH + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            // Grab the buy fee
            final String buyFeeInConfig = configEntries.getProperty(BUY_FEE_PROPERTY_NAME);
            if (buyFeeInConfig == null || buyFeeInConfig.length() == 0) {
                final String errorMsg = BUY_FEE_PROPERTY_NAME + CONFIG_IS_NULL_OR_ZERO_LENGTH + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            if (LOG.isInfoEnabled()) {
                LOG.info(BUY_FEE_PROPERTY_NAME + ": " + buyFeeInConfig + "%");
            }

            buyFeePercentage = new BigDecimal(buyFeeInConfig).divide(new BigDecimal("100"), 8, BigDecimal.ROUND_HALF_UP);
            if (LOG.isInfoEnabled()) {
                LOG.info("Buy fee % in BigDecimal format: " + buyFeePercentage);
            }

            // Grab the sell fee
            final String sellFeeInConfig = configEntries.getProperty(SELL_FEE_PROPERTY_NAME);
            if (sellFeeInConfig == null || sellFeeInConfig.length() == 0) {
                final String errorMsg = SELL_FEE_PROPERTY_NAME + CONFIG_IS_NULL_OR_ZERO_LENGTH + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            if (LOG.isInfoEnabled()) {
                LOG.info(SELL_FEE_PROPERTY_NAME + ": " + sellFeeInConfig + "%");
            }

            sellFeePercentage = new BigDecimal(sellFeeInConfig).divide(new BigDecimal("100"), 8, BigDecimal.ROUND_HALF_UP);
            if (LOG.isInfoEnabled()) {
                LOG.info("Sell fee % in BigDecimal format: " + sellFeePercentage);
            }

            /*
             * Grab the connection timeout
             */
            connectionTimeout = Integer.parseInt( // will barf if not a number; we want this to fail fast.
                    configEntries.getProperty(CONNECTION_TIMEOUT_PROPERTY_NAME));
            if (connectionTimeout == 0) {
                final String errorMsg = CONNECTION_TIMEOUT_PROPERTY_NAME + " cannot be 0 value!"
                        + " HINT: is the value set in the " + configFile + "?";
                LOG.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            if (LOG.isInfoEnabled()) {
                LOG.info(CONNECTION_TIMEOUT_PROPERTY_NAME + ": " + connectionTimeout);
            }

        } catch (IOException e) {
            final String errorMsg = "Failed to load Exchange config: " + configFile;
            LOG.error(errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                final String errorMsg = "Failed to close input stream for: " + configFile;
                LOG.error(errorMsg, e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    //  Util methods
    // ------------------------------------------------------------------------------------------------

    /**
     * Initialises the GSON layer.
     */
    private void initGson() {

        // We need to disable HTML escaping for this adapter else GSON will change = to unicode for query strings, e.g.
        // https://api.itbit.com/v1/wallets?userId=56DA621F --> https://api.itbit.com/v1/wallets?userId\u003d56DA621F
        final GsonBuilder gsonBuilder = new GsonBuilder().disableHtmlEscaping();
        gson = gsonBuilder.create();
    }

    /*
     * Hack for unit-testing config loading.
     */
    private static String getConfigFileLocation() {
        return CONFIG_FILE;
    }

    /*
     * Hack for unit-testing map params passed to transport layer.
     */
    private Map<String, String> getRequestParamMap() {
        return new HashMap<>();
    }
}