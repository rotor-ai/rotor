package ai.rotor.rotorvehicle.rotor_ctl;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Handler;

import timber.log.Timber;

public class RotorCtlService extends Thread implements ArduinoListener {
    private final static String TAG = "RotorCTLService";
    private State mRotorState;
    private Context mContext;
    private Arduino mRotorArduino;

    public enum State {
        HOMED,
        MANUAL,
        AUTONOMOUS
    }

    public RotorCtlService(Context context) {
        mContext = context;
        mRotorState = State.HOMED;
    }

    public void run() {
        mRotorArduino = new Arduino(mContext);
    }

    public State getRotorState() {
        return mRotorState;
    }


    public void setState(State stateChangeRequest) throws IllegalArgumentException {


        if (mRotorState == State.MANUAL && stateChangeRequest == State.AUTONOMOUS) {
            throw new IllegalArgumentException("Cannot move directly to autonomous mode from manual");
        }

        if (mRotorState == State.AUTONOMOUS && stateChangeRequest == State.MANUAL) {
            throw new IllegalArgumentException("Cannot move directly to manual mode from autonomous");
        }

        if (mRotorState != stateChangeRequest) {
            mRotorState = stateChangeRequest;
        }
    }

    public void sendCommand(String cmd) {
        Timber.d("Sending command: %s", cmd);
        mRotorArduino.send(cmd.getBytes());
    }

    @Override
    public void onArduinoAttached(UsbDevice device) {
        Timber.d( "Arduino attached...");
        mRotorArduino.open(device);
    }

    @Override
    public void onArduinoDetached() {
        Timber.d("Arduino detached");
    }

    @Override
    public void onArduinoOpened() {
        Timber.d("Arduino opened");
    }

    @Override
    public void onUsbPermissionDenied() {
        Timber.d( "Permission denied...");
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                mRotorArduino.reOpen();
            }
        }, 3000);
    }

    @Override
    public void onArduinoMessage(String message) {
        Timber.d("Received message: %s", message);
    }
}
