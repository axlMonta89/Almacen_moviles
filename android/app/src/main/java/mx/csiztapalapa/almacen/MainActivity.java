package mx.csiztapalapa.almacen;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * CS tiendas Iztapalapa — app de bodega.
 *
 * La app abre el sistema que corre en la PC de la bodega.  Existe por
 * dos razones que un navegador normal no resuelve bien:
 *   1. da permiso de cámara para leer códigos de barras, y
 *   2. acepta el certificado propio del servidor de la bodega sin que
 *      el usuario tenga que pasar por pantallas de advertencia.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "cs_almacen";
    private static final String CLAVE_URL = "servidor";
    private static final int PIDE_CAMARA = 101;

    private WebView web;
    private FrameLayout raiz;
    private SharedPreferences prefs;
    private String servidor = "";
    private String anfitrion = "";
    private PermissionRequest permisoPendiente;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        servidor = prefs.getString(CLAVE_URL, "");

        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.parseColor("#1b4332"));
        setContentView(raiz);

        web = new WebView(this);
        raiz.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        prepararWebView();

        if (servidor.isEmpty()) {
            pedirDireccion(true);
        } else {
            abrirServidor();
        }
    }

    // ------------------------------------------------------------------
    private void prepararWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setUserAgentString(s.getUserAgentString() + " CSIztapalapaApp/1.0");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest peticion) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (checkSelfPermission(Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            peticion.grant(peticion.getResources());
                        } else {
                            permisoPendiente = peticion;
                            requestPermissions(new String[]{Manifest.permission.CAMERA}, PIDE_CAMARA);
                        }
                    }
                });
            }
        });

        web.setWebViewClient(new WebViewClient() {
            /**
             * El servidor de la bodega usa un certificado que él mismo se
             * hizo (en una red local no hay de otra).  Se acepta sólo si
             * viene exactamente del servidor que configuró el usuario.
             */
            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler manejador, SslError error) {
                String host = Uri.parse(error.getUrl() == null ? "" : error.getUrl()).getHost();
                if (host != null && host.equals(anfitrion)) {
                    manejador.proceed();
                } else {
                    manejador.cancel();
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest peticion, WebResourceError error) {
                if (peticion != null && peticion.isForMainFrame()) {
                    mostrarFalla(error == null ? "" : String.valueOf(error.getDescription()));
                }
            }
        });

        web.addJavascriptInterface(new Puente(), "AppAndroid");
    }

    public class Puente {
        /** El sistema web llama esto desde el menú «Cambiar servidor». */
        @JavascriptInterface
        public void cambiarServidor() {
            runOnUiThread(new Runnable() {
                public void run() { pedirDireccion(false); }
            });
        }

        @JavascriptInterface
        public String version() { return "1.0"; }
    }

    // ------------------------------------------------------------------
    private void abrirServidor() {
        anfitrion = Uri.parse(servidor).getHost();
        quitarPantallaDeFalla();
        web.loadUrl(servidor);
    }

    private void pedirDireccion(final boolean primeraVez) {
        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (18 * getResources().getDisplayMetrics().density);
        caja.setPadding(p, p, p, 0);

        TextView ayuda = new TextView(this);
        ayuda.setText("Escribe la dirección que aparece en la ventana del servidor, "
                + "en la PC de la bodega. Usa la que empieza con https para que funcione la cámara.");
        ayuda.setTextSize(13);
        caja.addView(ayuda);

        final EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        campo.setHint("https://192.168.1.100:8443");
        campo.setText(servidor.isEmpty() ? "https://192.168.1.100:8443" : servidor);
        campo.setSelectAllOnFocus(true);
        caja.addView(campo);

        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle("Servidor de la bodega")
                .setView(caja)
                .setCancelable(!primeraVez)
                .setPositiveButton("Conectar", null);
        if (!primeraVez) d.setNegativeButton("Cancelar", null);

        final AlertDialog dialogo = d.create();
        dialogo.show();
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String url = campo.getText().toString().trim();
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                if (Uri.parse(url).getHost() == null) {
                    Toast.makeText(MainActivity.this, "Esa dirección no se entiende", Toast.LENGTH_LONG).show();
                    return;
                }
                servidor = url;
                prefs.edit().putString(CLAVE_URL, servidor).apply();
                dialogo.dismiss();
                abrirServidor();
            }
        });
    }

    // ------------------------------------------------------------------
    private View pantallaFalla;

    private void mostrarFalla(String detalle) {
        quitarPantallaDeFalla();
        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setGravity(Gravity.CENTER);
        caja.setBackgroundColor(Color.parseColor("#1b4332"));
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        caja.setPadding(p, p, p, p);

        TextView t = new TextView(this);
        t.setText("No se pudo conectar con la bodega.\n\n"
                + "Revisa que estés en el Wi-Fi de la bodega y que la PC tenga el\n"
                + "servidor encendido.\n\n" + servidor
                + (detalle == null || detalle.isEmpty() ? "" : "\n\n(" + detalle + ")"));
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        caja.addView(t);

        Button reintentar = new Button(this);
        reintentar.setText("Reintentar");
        reintentar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { abrirServidor(); }
        });
        caja.addView(reintentar);

        Button cambiar = new Button(this);
        cambiar.setText("Cambiar dirección");
        cambiar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pedirDireccion(false); }
        });
        caja.addView(cambiar);

        pantallaFalla = caja;
        raiz.addView(caja, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void quitarPantallaDeFalla() {
        if (pantallaFalla != null) {
            raiz.removeView(pantallaFalla);
            pantallaFalla = null;
        }
    }

    // ------------------------------------------------------------------
    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos, int[] resultados) {
        if (codigo == PIDE_CAMARA && permisoPendiente != null) {
            boolean concedido = resultados.length > 0 && resultados[0] == PackageManager.PERMISSION_GRANTED;
            if (concedido) {
                permisoPendiente.grant(permisoPendiente.getResources());
            } else {
                permisoPendiente.deny();
                Toast.makeText(this, "Sin permiso de cámara no se pueden leer códigos",
                        Toast.LENGTH_LONG).show();
            }
            permisoPendiente = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
