package es.uva.tfg.hector.SkyWalkerApp.persistence;

import android.annotation.SuppressLint;
import android.content.Context;

import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.JsonRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import es.uva.tfg.hector.SkyWalkerApp.R;
import es.uva.tfg.hector.SkyWalkerApp.business.MapPoint;
import es.uva.tfg.hector.SkyWalkerApp.business.PointOfInterest;
import es.uva.tfg.hector.SkyWalkerApp.business.Token;
import es.uva.tfg.hector.SkyWalkerApp.business.User;
import es.uva.tfg.hector.SkyWalkerApp.business.iBeaconFrame;

/**
 * Handler for server petitions, as there can only be one connection per time,
 * this singleton also manages the connection token.
 * @author Hector Del Campo Pando
 */
public class ServerFacade {

    /**
     * Enum for server errors.
     */
    public enum Errors {
        NO_CONNECTION, INVALID_USERNAME_OR_PASSWORD, INVALID_JSON, TIME_OUT, UNKNOWN
    }

    /**
     * Singleton instance.
     */
    @SuppressLint("StaticFieldLeak")
    private static ServerFacade instance;

    /**
     * Requests queue.
     */
    private final RequestQueue requestQueue;

    /**
     * Requests context.
     */
    private final Context context;

    /**
     * Retrieves the singleton instance.
     * @param context of the App to make petitions.
     * @return the singleton instance.
     */
    public static ServerFacade getInstance (Context context) {

        if (instance == null) {
            instance = new ServerFacade(context);
        }

        return instance;

    }

    /**
     * Creates a new instance of the requests queue.
     * @param context of the App.
     */
    private ServerFacade(Context context) {
        this.context = context.getApplicationContext();
        requestQueue = Volley.newRequestQueue(context);
    }

    /**
     * Retrieves a new connection, generating a new {@code Token}.
     * @param responseListener that will handle responses.
     * @param url of the server.
     * @param username of the user.
     * @param password of the user.
     */
    public void getToken (final OnServerResponse <Token> responseListener,
                                        final String url, final String username, final String password) {

        final String apiURL = url.concat("/api/authentication");

        JSONObject params = new JSONObject();
        try {
            params.put("login", username);
            params.put("password", password);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        JsonRequest<String> request = new JsonRequest<String>(Request.Method.POST, apiURL, params.toString(), new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {
                Token token = new Token(url, response);
                responseListener.onSuccess(token);
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Errors errorEnum = getServerError(error);
                responseListener.onError(errorEnum);
            }
        }) {
            @Override
            protected Response<String> parseNetworkResponse(NetworkResponse response) {
                String parsed;
                try {
                    parsed = new String(response.data, HttpHeaderParser.parseCharset(response.headers));
                } catch (UnsupportedEncodingException e) {
                    parsed = new String(response.data);
                }
                return Response.success(parsed, HttpHeaderParser.parseCacheHeaders(response));
            }
        };

        requestQueue.add(request);

    }

    /**
     * Asks the server for info about what data to transmit as iBeacon frame.
     * @param responseListener that will handle responses.
     * @param username of the user that wants to transmit its position.
     */
    public void registerAsBeacon (final OnServerResponse <iBeaconFrame> responseListener, final String username) {

        if (!User.getInstance().isLogged()) {
            throw new IllegalStateException("Cannot retrieve tags without a established connection");
        }

        String url = User.getInstance().getToken().getURL().concat("/api/centers/" + User.getInstance().getCenter().getId() + "/tags/");

        JSONObject body = new JSONObject();
        try {
            body.put("name", username);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        JsonRequest<JSONObject> request = new JsonObjectRequest(Request.Method.POST, url, body, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject response) {
                try {
                    final String UUID = "3E8C0296-168B-4940-ADB0-B3088F7EE30E";
                    final int major = response.getInt("major");
                    final int minor =  response.getInt("minor");
                    final iBeaconFrame frame = new iBeaconFrame(UUID, major, minor);

                    responseListener.onSuccess(frame);
                } catch (JSONException e) {
                    responseListener.onError(Errors.INVALID_JSON);
                }

            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Errors errorNum = getServerError(error);
                responseListener.onError(errorNum);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " +  User.getInstance().getToken().getToken());
                return headers;
            }
        };

        requestQueue.add(request);

    }

    /**
     * Retrieves a center's receivers.
     * @param responseListener that will handle responses.
     * @param center whose receivers must be retreived.
     */
    public void getCenterReceivers (final OnServerResponse <List<MapPoint>> responseListener, final int center) {

        if (!User.getInstance().isLogged()) {
            throw new IllegalStateException("Cannot retrieve tags without a established connection");
        }

        String url =  User.getInstance().getToken().getURL().concat("/api/centers/" + center + "/rdhubs");

        JsonRequest<JSONArray> request = new JsonArrayRequest(Request.Method.GET, url, null, new Response.Listener<JSONArray>() {
            @Override
            public void onResponse(JSONArray response) {

                final List<MapPoint> receivers = new ArrayList<>();

                for(int i = 0; i < response.length(); i++) {

                    try {
                        JSONObject json = response.getJSONObject(i);

                        final int id = json.getInt("id");
                        final float x = (float) json.getDouble("x");
                        final float y = (float) json.getDouble("y");
                        final int z = json.getInt("z");

                        final MapPoint receiver = new MapPoint(id, x, y, z);
                        receivers.add(receiver);

                    } catch (JSONException e) {
                        responseListener.onError(Errors.INVALID_JSON);
                    }
                }

                responseListener.onSuccess(receivers);

            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Errors errorNum = getServerError(error);
                responseListener.onError(errorNum);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + User.getInstance().getToken().getToken());
                return headers;
            }
        };

        requestQueue.add(request);

    }

    /**
     * Retrieves all available tags for a given token.
     * @param responseListener that will handle responses.
     */
    public void getAvailableTags(final OnServerResponse <List<PointOfInterest>> responseListener) {

        if (!User.getInstance().isLogged()) {
            throw new IllegalStateException("Cannot retrieve tags without a established connection");
        }

        String url =  User.getInstance().getToken().getURL().concat("/api/centers/" + User.getInstance().getCenter().getId() + "/tags");

        JsonRequest<JSONArray> request = new JsonArrayRequest(Request.Method.GET, url, null, new Response.Listener<JSONArray>() {
            @Override
            public void onResponse(JSONArray response) {
                List<PointOfInterest> points = new ArrayList<>(response.length());
                try {

                    for (int i = 0; i < response.length(); i++) {
                        JSONObject json = response.getJSONObject(i);

                        final int id = json.getInt("id");
                        final String name;
                        if (json.has("name")) {
                            name = json.getString("name");
                        } else {
                            name = context.getString(R.string.not_assigned);
                        }
                        PointOfInterest point = new PointOfInterest(id, name);
                        points.add(point);
                    }
                    responseListener.onSuccess(points);

                } catch (JSONException e) {
                    e.printStackTrace();
                    responseListener.onError(Errors.INVALID_JSON);
                }
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Errors errorNum = getServerError(error);
                responseListener.onError(errorNum);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " +  User.getInstance().getToken().getToken());
                return headers;
            }
        };

        requestQueue.add(request);

    }

    /**
     * Retrieves the last known position for a given tag.
     * @param responseListener that will handle responses.
     * @param point to ask for.
     */
    public void getLastPosition (final OnServerResponse <MapPoint> responseListener, final MapPoint point) {

        if (!User.getInstance().isLogged()) {
            throw new IllegalStateException("Cannot retrieve tags without a established connection");
        }

        String url = User.getInstance().getToken().getURL().concat("/api/centers/" + User.getInstance().getCenter().getId() + "/tags/" + point.getId());

        JsonRequest<JSONObject> request = new JsonObjectRequest(Request.Method.GET, url, null, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject response) {
                try {
                    if (!response.has("nearest_rdhub")) {
                        return;
                    }

                    final int receiverId = response.getInt("nearest_rdhub");
                    final MapPoint receiver = User.getInstance().getCenter().getReceiver(receiverId);
                    final MapPoint newPosition =
                            new MapPoint(
                                    point.getId(),
                                    receiver.getX(),
                                    receiver.getY(),
                                    receiver.getZ());

                    responseListener.onSuccess(newPosition);
                } catch (JSONException e) {
                    e.printStackTrace();
                    responseListener.onError(Errors.INVALID_JSON);
                }
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Errors errorNum = getServerError(error);
                responseListener.onError(errorNum);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + User.getInstance().getToken().getToken());
                return headers;
            }
        };

        requestQueue.add(request);

    }

    /**
     * Retrieves actual server error reason
     * @param error given by server
     * @return an enum for the error
     */
    private static Errors getServerError(VolleyError error) {

        Errors errorEnum;

        if (error instanceof TimeoutError) {
            errorEnum = Errors.TIME_OUT;
        } else if (error instanceof NoConnectionError) {
            errorEnum = Errors.NO_CONNECTION;
        } else if (error instanceof AuthFailureError) {
          errorEnum = Errors.INVALID_USERNAME_OR_PASSWORD;
        } else if (error == null || error.networkResponse == null) {
            errorEnum = Errors.UNKNOWN;
        } else {
            switch (error.networkResponse.statusCode) {
                default:
                    errorEnum = Errors.UNKNOWN;
                    break;
            }
        }

        return errorEnum;

    }
    
    /**
     * Interface that must be implemented by caller in order to recieve responses
     * @author Hector Del Campo Pando
     */
    public interface OnServerResponse <T> {

        /**
         * Callback for success petition
         * @param response of the server
         */
        void onSuccess (T response);

        /**
         * Callback for error
         * @param error given by server
         */
        void onError (Errors error);

    }

}
