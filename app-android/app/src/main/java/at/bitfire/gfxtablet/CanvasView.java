package at.bitfire.gfxtablet;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import at.bitfire.gfxtablet.NetEvent.Type;

@SuppressLint("ViewConstructor")
public class CanvasView extends View implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "GfxTablet.CanvasView";

	private enum InRangeStatus {
		OutOfRange,
		InRange,
		FakeInRange
	}

    final SharedPreferences settings;
    NetworkClient netClient;
	boolean acceptStylusOnly;
	int maxX, maxY;
	InRangeStatus inRangeStatus;

	Paint paint = new Paint();
	Path drawing = new Path();
	boolean isDrawing = false;

    // setup

    public CanvasView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);

        // view is disabled until a network client is set
        setEnabled(false);

        settings = PreferenceManager.getDefaultSharedPreferences(context);
        settings.registerOnSharedPreferenceChangeListener(this);
        setBackground();
        setInputMethods();
		inRangeStatus = InRangeStatus.OutOfRange;

		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(5);
		paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setNetworkClient(NetworkClient networkClient) {
        netClient = networkClient;
        setEnabled(true);
    }

	public void clearDrawing() {
		drawing.reset();
		invalidate();
	}

	public void toggleDrawing() {
		isDrawing = !isDrawing;
		invalidate();
	}


    // settings

    protected void setBackground() {
        if (settings.getBoolean(SettingsActivity.KEY_DARK_CANVAS, false))
            setBackgroundColor(Color.BLACK);
        else
            setBackgroundResource(R.drawable.bg_grid_pattern);
    }

    protected void setInputMethods() {
        acceptStylusOnly = settings.getBoolean(SettingsActivity.KEY_PREF_STYLUS_ONLY, false);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        switch (key) {
            case SettingsActivity.KEY_PREF_STYLUS_ONLY:
                setInputMethods();
                break;
            case SettingsActivity.KEY_DARK_CANVAS:
                setBackground();
                break;
        }
    }


    // drawing

    @Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        Log.i(TAG, "Canvas size changed: " + w + "x" + h + " (before: " + oldw + "x" + oldh + ")");
		maxX = w;
		maxY = h;
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		if (settings.getBoolean(SettingsActivity.KEY_DARK_CANVAS, false))
			paint.setColor(Color.WHITE);
		else
			paint.setColor(Color.BLACK);
		canvas.drawPath(drawing, paint);
	}

	@Override
	public boolean onGenericMotionEvent(MotionEvent event) {
		if (isEnabled()) {
			for (int ptr = 0; ptr < event.getPointerCount(); ptr++)
				if (!acceptStylusOnly || (event.getToolType(ptr) == MotionEvent.TOOL_TYPE_STYLUS)) {
					short nx = normalizeX(event.getX(ptr)),
							ny = normalizeY(event.getY(ptr)),
							npressure = normalizePressure(event.getPressure(ptr));
					Log.v(TAG, String.format("Generic motion event logged: %f|%f, pressure %f", event.getX(ptr), event.getY(ptr), event.getPressure(ptr)));
					switch (event.getActionMasked()) {
					case MotionEvent.ACTION_HOVER_MOVE:
						netClient.getQueue().add(new NetEvent(Type.TYPE_MOTION, nx, ny, npressure));
						break;
					case MotionEvent.ACTION_HOVER_ENTER:
						inRangeStatus = InRangeStatus.InRange;
						netClient.getQueue().add(new NetEvent(Type.TYPE_BUTTON, nx, ny, npressure, -1, true));
						break;
					case MotionEvent.ACTION_HOVER_EXIT:
						inRangeStatus = InRangeStatus.OutOfRange;
						netClient.getQueue().add(new NetEvent(Type.TYPE_BUTTON, nx, ny, npressure, -1, false));
						break;
					}
				}
			return true;
		}
		return false;
	}
	
	@Override
	public boolean onTouchEvent(@NonNull MotionEvent event) {
		if (isEnabled()) {
			for (int ptr = 0; ptr < event.getPointerCount(); ptr++)
				if (!acceptStylusOnly || (event.getToolType(ptr) == MotionEvent.TOOL_TYPE_STYLUS)) {

					float x = event.getX(ptr), y = event.getY(ptr);
					short nx = normalizeX(x), ny = normalizeY(y),
							npressure = normalizePressure(event.getPressure(ptr));
					Log.v(TAG, String.format("Touch event logged: action %d @ %f|%f (pressure %f)", event.getActionMasked(), event.getX(ptr), event.getY(ptr), event.getPressure(ptr)));
					switch (event.getActionMasked()) {
					case MotionEvent.ACTION_MOVE:
						netClient.getQueue().add(new NetEvent(Type.TYPE_MOTION, nx, ny, npressure));

						if(isDrawing) {
							drawing.lineTo(x, y);
							invalidate();
						}
						break;
					case MotionEvent.ACTION_DOWN:
						if (inRangeStatus == inRangeStatus.OutOfRange) {
							inRangeStatus = inRangeStatus.FakeInRange;
							netClient.getQueue().add(new NetEvent(Type.TYPE_BUTTON, nx, ny, (short)0, -1, true));
						}
						netClient.getQueue().add(new NetEvent(Type.TYPE_BUTTON, nx, ny, npressure, 0, true));

						if(isDrawing) {
							drawing.moveTo(x, y);
							invalidate();
						}
						break;
					case MotionEvent.ACTION_UP:
					case MotionEvent.ACTION_CANCEL:
						netClient.getQueue().add(new NetEvent(Type.TYPE_BUTTON, nx, ny, npressure, 0, false));
						if (inRangeStatus == inRangeStatus.FakeInRange) {
							inRangeStatus = inRangeStatus.OutOfRange;
							netClient.getQueue().add(new NetEvent(Type.TYPE_BUTTON, nx, ny, (short)0, -1, false));
						}
						break;
					}
						
				}
			return true;
		}
		return false;
	}
	
	// these overflow and wrap around to negative short values, but thankfully Java will continue
	// on regardless, so we can just ignore Java's interpretation of them and send them anyway.
	short normalizeX(float x) {
		return (short)(Math.min(Math.max(0, x), maxX) * 2*Short.MAX_VALUE/maxX);
	}
	
	short normalizeY(float x) {
		return (short)(Math.min(Math.max(0, x), maxY) * 2*Short.MAX_VALUE/maxY);
	}
	
	short normalizePressure(float x) {
		return (short)(Math.min(Math.max(0, x), 2.0) * Short.MAX_VALUE);
	}

}
