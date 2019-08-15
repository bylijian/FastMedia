package com.bylijian.fastmedia.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.support.v7.widget.AppCompatImageView;
import android.util.AttributeSet;


public class FocusView extends AppCompatImageView {
    private ValueAnimator mFocusAnimator;

    public FocusView(Context context) {
        super(context);
    }

    public FocusView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FocusView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void showFocusView(float touchX, float touchY) {
        ObjectAnimator animator = new ObjectAnimator();
        setX(touchX - getMeasuredWidth() / 2);
        setY(touchY - getMeasuredHeight() / 2);
        mFocusAnimator = ValueAnimator.ofFloat(0, 1).setDuration(500);
        mFocusAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();
                setScaleX(2 - value);
                setScaleY(2 - value);
            }
        });
        mFocusAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mFocusAnimator = null;
                setVisibility(INVISIBLE);
            }
        });
        setVisibility(VISIBLE);
        mFocusAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mFocusAnimator != null) {
            mFocusAnimator.cancel();
        }
    }
}
