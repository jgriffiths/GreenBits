package com.greenaddress.greenbits.ui.preferences;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.greenaddress.greenapi.JSONMap;
import com.greenaddress.greenbits.ui.CB;
import com.greenaddress.greenbits.ui.UI;
import com.greenaddress.greenbits.ui.R;
import com.greenaddress.greenbits.ui.TwoFactorActivity;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.HashMap;
import java.util.Map;

public class TwoFactorPreferenceFragment extends GAPreferenceFragment
    implements Preference.OnPreferenceClickListener {

    private static final String TAG = GAPreferenceFragment.class.getSimpleName();
    private static final int REQUEST_ENABLE_2FA = 0;
    private static final String NLOCKTIME_EMAILS = "NLocktimeEmails";

    private Map<String, String> mLocalizedMap; // 2FA method to localized description
    private Preference mLimitsPref;
    private Preference mSendNLocktimePref;
    private Preference mTwoFactorResetPref;

    private CheckBoxPreference getPref(final String method) {
        return find("twoFac" + method);
    }

    private static boolean isEnabled(final Map<?, ?> twoFacConfig, final String method) {
        return twoFacConfig.get(method.toLowerCase(Locale.US)).equals(true);
    }

    private CheckBoxPreference setupCheckbox(final Map<?, ?> twoFacConfig, final String method) {
        final CheckBoxPreference c = getPref(method);
        if (method.equals(NLOCKTIME_EMAILS))
            c.setChecked(isNlocktimeConfig(true));
        else
            c.setChecked(isEnabled(twoFacConfig, method));
        c.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(final Preference p, final Object newValue) {
                if (method.equals(NLOCKTIME_EMAILS))
                    setNlocktimeConfig((Boolean) newValue);
                else
                    prompt2FAChange(method, (Boolean) newValue);
                return false;
            }
        });
        return c;
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!verifyServiceOK()) {
            Log.d(TAG, "Avoiding create on logged out service");
            return;
        }

        mLocalizedMap = UI.getTwoFactorLookup(getResources());

        addPreferencesFromResource(R.xml.preference_twofactor);
        setHasOptionsMenu(true);

        final Map<?, ?> twoFacConfig = mService == null ? null : mService.getTwoFactorConfig();
        if (twoFacConfig == null || twoFacConfig.isEmpty()) {
            // An additional check to verifyServiceOK: We must have our 2fa data
            final GaPreferenceActivity activity = (GaPreferenceActivity) getActivity();
            if (activity != null) {
                activity.toast(R.string.err_send_not_connected_will_resume);
                activity.finish();
            }
            return;
        }
        final CheckBoxPreference emailCB = setupCheckbox(twoFacConfig, "Email");
        setupCheckbox(twoFacConfig, "Gauth");
        setupCheckbox(twoFacConfig, "SMS");
        setupCheckbox(twoFacConfig, "Phone");
        final boolean haveAny = mService.hasAnyTwoFactor();

        mLimitsPref = find("twoFacLimits");
        mLimitsPref.setOnPreferenceClickListener(this);
        // Can only set limits if at least one 2FA method is available
        setLimitsText(haveAny);

        mSendNLocktimePref = find("send_nlocktime");
        if (mService.isElements()) {
            removePreference(getPref(NLOCKTIME_EMAILS));
            removePreference(mSendNLocktimePref);
        } else {
            final CheckBoxPreference nlockCB = setupCheckbox(twoFacConfig, NLOCKTIME_EMAILS);
            final Boolean emailEnabled = emailCB.isChecked();
            nlockCB.setEnabled(emailEnabled);
            mSendNLocktimePref.setEnabled(emailEnabled);
            mSendNLocktimePref.setOnPreferenceClickListener(this);
        }
        mTwoFactorResetPref = find("reset_twofactor");

        if (haveAny)
            mTwoFactorResetPref.setOnPreferenceClickListener(this);
        else
            removePreference(mTwoFactorResetPref);
    }

    private boolean isNlocktimeConfig(final Boolean enabled) {
        Boolean b = false;
        final Map<String, Object> outer;
        outer = (Map) mService.getUserConfig("notifications_settings");
        if (outer != null)
            b = Boolean.TRUE.equals(outer.get("email_incoming")) &&
                Boolean.TRUE.equals(outer.get("email_outgoing"));
        return b.equals(enabled);
    }

    private void setNlocktimeConfig(final Boolean enabled) {
        if (mService.isElements() || isNlocktimeConfig(enabled))
            return; // Nothing to do
        final Map<String, Object> inner, outer;
        inner = ImmutableMap.of("email_incoming", (Object) enabled,
                                "email_outgoing", (Object) enabled);
        outer = ImmutableMap.of("notifications_settings", (Object) inner);
        mService.setUserConfig(Maps.newHashMap(outer), true /* Immediate */);
        getPref(NLOCKTIME_EMAILS).setChecked(enabled);
    }

    private void prompt2FAChange(final String method, final Boolean newValue) {
        if (newValue) {
            final Intent intent = new Intent(getActivity(), TwoFactorActivity.class);
            intent.putExtra("method", method);
            startActivityForResult(intent, REQUEST_ENABLE_2FA);
            return;
        }

        final boolean skipChoice = false;
        final Dialog dlg = UI.popupTwoFactorChoice(getActivity(), mService, skipChoice,
                                                   new CB.Runnable1T<String>() {
            public void run(final String withMethod) {
                disable2FA(method, withMethod);
            }
        });
        if (dlg != null)
            dlg.show();
    }

    private void disable2FA(final String method, final String withMethod) {
        if (!withMethod.equals("gauth")) {
            final Map<String, String> data = new HashMap<>();
            data.put("method", method.toLowerCase(Locale.US));
            mService.requestTwoFacCode(withMethod, "disable_2fa", data);
        }

        final View v = inflatePinDialog(withMethod);
        final EditText codeText = UI.find(v, R.id.btchipPINValue);

        UI.popup(this.getActivity(), "2FA")
                  .customView(v, true)
                  .onPositive(new MaterialDialog.SingleButtonCallback() {
                      @Override
                      public void onClick(final MaterialDialog dialog, final DialogAction which) {
                          mService.getExecutor().submit(new Callable<Void>() {
                              @Override
                              public Void call() {
                                  disable2FAImpl(method, withMethod, UI.getText(codeText));
                                  return null;
                              }
                          });
                      }
                  }).build().show();
    }

    private void disable2FAImpl(final String method, final String withMethod, final String code) {
        try {
            if (mService.disableTwoFactor(method.toLowerCase(Locale.US), mService.make2FAData(withMethod, code))) {
                getActivity().runOnUiThread(new Runnable() {
                    public void run() {
                        change2FA(method, false);
                    }
                });
                return;
            }
        } catch (final Exception e) {
            // Toast below
        }
        UI.toast(getActivity(), "Error disabling 2FA", Toast.LENGTH_SHORT);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_2FA && resultCode == Activity.RESULT_OK && data != null)
            change2FA(data.getStringExtra("method"), true);
    }

    private void change2FA(final String method, final Boolean checked) {
        if (method.equals("reset"))
            return;

        getPref(method).setChecked(checked);
        if (method.equals("Email")) {
            // Reset nlocktime prefs when the user changes email 2FA
            setNlocktimeConfig(checked);
            if (!mService.isElements())
                getPref(NLOCKTIME_EMAILS).setEnabled(checked);
        }
        final boolean haveAny = checked || mService.hasAnyTwoFactor();
        setLimitsText(haveAny);
        if (!haveAny)
            removePreference(mTwoFactorResetPref);
    }

    private void setLimitsText(final Boolean enabled) {
        mLimitsPref.setEnabled(enabled);
        if (!enabled) {
            mLimitsPref.setSummary(R.string.twoFacLimitDisabled);
            return;
        }
        final JSONMap limits = mService.getSpendingLimits();
        final BigDecimal unscaled = new BigDecimal(limits.getLong("total"));
        final boolean isFiat = limits.getBool("is_fiat");
        String trimmed = unscaled.movePointLeft(isFiat ? 2 : 8).toPlainString();
        trimmed = trimmed.indexOf('.') < 0 ? trimmed : trimmed.replaceAll("0*$", "").replaceAll("\\.$", "");
        final String limitText;
        if (mService.isElements())
            limitText = mService.getAssetSymbol() + ' ' + trimmed;
        else
            limitText = trimmed + ' ' + (isFiat ? mService.getFiatCurrency() : "BTC");

        mLimitsPref.setSummary(getString(R.string.twoFacLimitEnabled, limitText));
    }

    private View inflatePinDialog(final String withMethod) {
        final View v = UI.inflateDialog(getActivity(), R.layout.dialog_btchip_pin);

        final TextView promptText = UI.find(v, R.id.btchipPinPrompt);
        promptText.setText(getString(R.string.twoFacProvideConfirmationCode,
                                     mLocalizedMap.get(withMethod)));
        return v;
    }

    @Override
    public boolean onPreferenceClick(final Preference preference) {
        if (preference == mLimitsPref)
            return onLimitsPreferenceClicked(preference);
        if (preference == mSendNLocktimePref)
            return onSendNLocktimeClicked(preference);
        if (preference == mTwoFactorResetPref)
            return onTwoFactorResetClicked();
        return false;
    }

    private boolean onLimitsPreferenceClicked(final Preference preference) {
        final View v = UI.inflateDialog(getActivity(), R.layout.dialog_set_limits);
        final Spinner currencySpinner = UI.find(v, R.id.set_limits_currency);
        final EditText amountEdit = UI.find(v, R.id.set_limits_amount);

        final String[] currencies;
        if (mService.isElements())
            currencies = new String[]{mService.getAssetSymbol()};
        else if (!mService.hasFiatRate())
            currencies = new String[]{"BTC"};
        else
            currencies = new String[]{"BTC", mService.getFiatCurrency()};

        final ArrayAdapter<String> adapter;
        adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, currencies);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        currencySpinner.setAdapter(adapter);
        if (currencies.length > 1 && mService.getSpendingLimits().getBool("is_fiat"))
            currencySpinner.setSelection(1);

        final MaterialDialog dialog;
        dialog = UI.popup(getActivity(), R.string.twoFacLimitTitle)
                   .customView(v, true)
                   .onPositive(new MaterialDialog.SingleButtonCallback() {
                       @Override
                       public void onClick(final MaterialDialog dlg, final DialogAction which) {
                           try {
                               final String currency = currencySpinner.getSelectedItem().toString();
                               final String amount = UI.getText(amountEdit);
                               final boolean isFiat = !currency.equals(currencies[0]);
                               onNewLimitsSelected(TextUtils.isEmpty(amount) ? "0" : amount, isFiat);
                           } catch (final Exception e) {
                               UI.toast(getActivity(), "Error setting limits", Toast.LENGTH_LONG);
                           }
                       }
                   }).build();
        UI.showDialog(dialog);
        return false;
    }

    private boolean onSendNLocktimeClicked(final Preference preference) {
        mService.getExecutor().submit(new Callable<Void>() {
            @Override
            public Void call() {
                try {
                    mService.sendNLocktime();
                } catch (final Exception e) {
                    // Ignore, user can send again if email fails to arrive
                }
                return null;
            }
        });
        UI.toast(getActivity(), R.string.nlocktime_request_sent, Toast.LENGTH_SHORT);
        return false;
    }

    private void onNewLimitsSelected(final String amount, final boolean isFiat) {
        final BigDecimal unscaled = new BigDecimal(amount);
        final BigDecimal scaled = unscaled.movePointRight(isFiat ? 2 : 8);
        final long limit = scaled.longValue();

        // Only requires 2FA if we have it setup and we are increasing the limit
        final boolean skipChoice = !mService.doesLimitChangeRequireTwoFactor(limit, isFiat);
        if (skipChoice) {
            setSpendingLimits(mService.makeLimitsData(limit, isFiat), null);
            return;
        }

        final MaterialDialog mTwoFactor = UI.popupTwoFactorChoice(getActivity(), mService, skipChoice, new CB.Runnable1T<String>() {
            public void run(final String method) {
                final View v = inflatePinDialog(method);
                final EditText codeText = UI.find(v, R.id.btchipPINValue);
                final JSONMap limitsData = mService.makeLimitsData(limit, isFiat);

                if (!method.equals("gauth"))
                    mService.requestTwoFacCode(method, "change_tx_limits", limitsData.mData);

                UI.popup(getActivity(), "Enter TwoFactor Code")
                  .customView(v, true)
                  .onPositive(new MaterialDialog.SingleButtonCallback() {
                      @Override
                      public void onClick(final MaterialDialog dialog, final DialogAction which) {
                          final String code = UI.getText(codeText);
                          setSpendingLimits(limitsData, mService.make2FAData(method, code));
                      }
                  }).build().show();
            }
        });
        if (mTwoFactor != null)
            mTwoFactor.show();
    }

    private void setSpendingLimits(final JSONMap limitsData,
                                   final Map<String, String> twoFacData) {
        try {
            mService.setSpendingLimits(limitsData, twoFacData);
            setLimitsText(true);
        } catch (final Exception e) {
            e.printStackTrace();
            UI.toast(getActivity(), "Failed", Toast.LENGTH_SHORT);
        }
    }

    public boolean onTwoFactorResetClicked() {
        prompt2FAChange("reset", true);
        return false;
    }
}
