package com.e_gineering.secretknock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.jakewharton.rxbinding.view.RxView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import butterknife.BindView;
import butterknife.ButterKnife;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.schedulers.TimeInterval;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity {

	private final String SHARED_PREFS = "sharedPrefs";
	private final String DURATIONS = "durations";
	private final Long ALLOWED_ERROR = 100L;
	private Animation an;

	@BindView(R.id.unlocked)
	ImageView unlocked;

	@BindView(R.id.locked)
	ImageView locked;

	@BindView(R.id.recording_message)
	TextView recordingMessage;

	@BindView(R.id.message)
	EditText message;

	@BindView(R.id.hidden_message)
	TextView hiddenMessage;

	@BindView(R.id.textview)
	TextView textView;

	@BindView(R.id.spinning_wheel)
	View spinningWheel;

	SharedPreferences sharedPreferences;
	SharedPreferences.Editor editor;

	Subscription clickSubscription;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		ButterKnife.bind(this);

		sharedPreferences = getSharedPreferences(SHARED_PREFS, MODE_PRIVATE);
		editor = sharedPreferences.edit();

		an = new RotateAnimation(0.0f,
				360.0f,Animation.RELATIVE_TO_SELF, 0.5f,
				Animation.RELATIVE_TO_SELF,
				0.5f);

		// Set the animation's parameters
		an.setInterpolator(new LinearInterpolator());
		an.setDuration(7000);               // duration in ms
		an.setRepeatCount(-1);                // -1 = infinite repeated
	}

	Observable.Transformer<Void, List<TimeInterval<Void>>> collectTapSequence() {
		return observable -> observable
				.observeOn(Schedulers.io())
				.doOnNext(clickEvent -> Timber.d("tap"))
				.timeInterval()
				.skip(1)
				.timeout(timeInterval -> Observable.timer(1500, TimeUnit.MILLISECONDS), Observable.empty())
				.toList()
				.observeOn(AndroidSchedulers.mainThread());
	}

	private void initializeRecorder() {
		clickSubscription = RxView.clicks(recordingMessage)
				.compose(collectTapSequence())
				.subscribe(new Subscriber<List<TimeInterval<Void>>>() {
					@Override
					public void onCompleted() {
						Timber.v("tapsSubscriber onCompleted");
					}

					@Override
					public void onError(Throwable e) {
						Timber.v(e, "ruh roh");
					}

					@Override
					public void onNext(List<TimeInterval<Void>> durations) {
						Timber.v("onNext");

						if (patternIsValid(durations)) {
							storePattern(durations);
							activateLock();
						} else {
							Timber.v("pattern invalid");
							Toast.makeText(MainActivity.this, R.string.pattern_invalid, Toast.LENGTH_SHORT).show();
							Handler handler = new Handler();
							handler.post(()-> initializeRecorder());
						}

					}
				}
		);

	}

	public void initializeTapToUnlock() {
		clickSubscription = RxView.clicks(hiddenMessage)
				.doOnNext(clickEvent -> {
					Timber.v("unlock tap");
					showBorderAnimation();
				})
				.compose(collectTapSequence())
				.subscribe(new Subscriber<List<TimeInterval<Void>>>() {
					@Override
					public void onCompleted() {
						hideBorderAnimation();
					}

					@Override
					public void onError(Throwable e) {
						Timber.v(e, "ruh roh");
					}

					@Override
					public void onNext(List<TimeInterval<Void>> durations) {
						Timber.v("onNext");

						if (patternMatches(durations)) {
							Timber.v("lock deactivated");
							deactivateLock();
						} else {
							Timber.v("pattern incorrect");
							Toast.makeText(MainActivity.this, R.string.pattern_incorrect, Toast.LENGTH_SHORT).show();
							hiddenMessage.setBackgroundResource(R.drawable.message_background);
							Handler handler = new Handler();
							handler.post(()-> initializeTapToUnlock());
						}

					}
				});

	}

	private void showBorderAnimation() {
		spinningWheel.setVisibility(View.VISIBLE);
		spinningWheel.startAnimation(an);
		hiddenMessage.setBackgroundResource(R.drawable.spinning_wheel_foreground);
	}

	private void hideBorderAnimation() {
		spinningWheel.setAnimation(null);
		spinningWheel.setVisibility(View.GONE);
	}

	public void onRecordClick(View view) {
		Timber.v("record clicked");

		// hide soft keyboard
		View currentView = this.getCurrentFocus();
		if (currentView != null) {
			InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
			imm.hideSoftInputFromWindow(currentView.getWindowToken(), 0);
		}

		message.setVisibility(View.GONE);
		recordingMessage.setVisibility(View.VISIBLE);
		textView.setText(R.string.record_instruction);
		spinningWheel.startAnimation(an);
		spinningWheel.setVisibility(View.VISIBLE);
		initializeRecorder();
	}

	private void activateLock() {
		Timber.v("lock activated");
		textView.setText(R.string.unlock_instruction);
		recordingMessage.setVisibility(View.GONE);
		hideBorderAnimation();
		unlocked.setVisibility(View.GONE);
		locked.setVisibility(View.VISIBLE);
		hiddenMessage.setBackgroundResource(R.drawable.message_background);
		hiddenMessage.setVisibility(View.VISIBLE);
		initializeTapToUnlock();
	}

	private void deactivateLock() {
		textView.setText(R.string.initial_instruction);
		locked.setVisibility(View.GONE);
		hiddenMessage.setVisibility(View.GONE);
		unlocked.setVisibility(View.VISIBLE);
		message.setVisibility(View.VISIBLE);
		clearPattern();
	}

	private boolean patternIsValid(List<TimeInterval<Void>> durations) {
		return durations.size() > 1;
	}

	private boolean patternMatches(List<TimeInterval<Void>> durations) {
		Long[] durationArray = convertTimeIntervalsToLongs(durations);

		List<Long> storedPattern = retrievePattern();
		Timber.v("storedPattern: %s", storedPattern.toString());
		Timber.v("enteredPattern: %s", durations.toString());
		if (durations.size() != storedPattern.size()) {
			Timber.v("patterns not same number of taps");
			return false;
		}

		// scale entered pattern to be same length as stored pattern
		Long totalStoredPatternDuration = 0L;
		Long totalEnteredPatternDuration = 0L;
		for (int i = 0; i < storedPattern.size(); i++) {
			totalStoredPatternDuration += storedPattern.get(i);
			totalEnteredPatternDuration += durationArray[i];
		}
		double scalingFactor = (double) totalStoredPatternDuration / (double) totalEnteredPatternDuration;

		// check stored pattern versus scaled pattern
		for (int i = 0; i < storedPattern.size(); i++) {
			Timber.v("stored:%s entered:%s scaled:%s", storedPattern.get(i), durations.get(i), Math.round(scalingFactor * durationArray[i]));
			if (Math.abs(storedPattern.get(i) - Math.round(scalingFactor * durationArray[i])) > ALLOWED_ERROR) {
				return false;
			}
		}

		return true;
	}

	private void storePattern(List<TimeInterval<Void>> durations) {
		Long[] durationArray = convertTimeIntervalsToLongs(durations);
		editor.putString(DURATIONS, TextUtils.join(",", durationArray)).apply();
		Timber.v("Pattern stored.");
	}

	private Long[] convertTimeIntervalsToLongs(List<TimeInterval<Void>> durations) {
		Long[] durationArray = new Long[durations.size()];
		for (int i = 0; i < durations.size(); i++) {
			durationArray[i] = durations.get(i).getIntervalInMilliseconds();
		}
		return durationArray;
	}

	private void clearPattern() {
		editor.remove(DURATIONS);
	}

	private List<Long> retrievePattern() {
		String[] list = TextUtils.split(sharedPreferences.getString(DURATIONS, null), ",");
		ArrayList<String> arrayListString = new ArrayList<>(Arrays.asList(list));
		ArrayList<Long> arrayListLong = new ArrayList<>();

		for (String str : arrayListString) {
			arrayListLong.add(Long.parseLong(str));
		}

		Timber.v("retrieved list of durations:%s", arrayListLong.toString());

		return arrayListLong;

	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		clickSubscription.unsubscribe();
	}
}
