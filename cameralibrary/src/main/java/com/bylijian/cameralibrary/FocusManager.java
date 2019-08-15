package com.bylijian.cameralibrary;

import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CameraCharacteristics;

/**
 * 用于计算对焦的区域
 *
 * @see https://www.jianshu.com/p/49dcab6a1f75
 * Created by wenzhe on 5/2/17.
 */

public class FocusManager {
    private static final String TAG = "FocusManager";
    private float currentX;
    private float currentY;
    private CoordinateTransformer mTransformer;
    private Rect mPreviewRect;
    private Rect mFocusRect;

    public FocusManager() {
        mFocusRect = new Rect();
    }

    public void onPreviewChanged(int width, int height, CameraCharacteristics c) {
        if (c == null) {
            //camera1
            mPreviewRect = new Rect(0, 0, width, height);
        } else {
            //camera2
            mPreviewRect = new Rect(0, 0, width, height);
            mTransformer = new CoordinateTransformer(c, rectToRectF(mPreviewRect));
        }
    }

    public Rect getFocusArea(float x, float y, boolean isFocusArea) {
        currentX = x;
        currentY = y;
        if (mTransformer == null) {
            //camera1
            Rect rect = getFocusArea(x, y, mPreviewRect.width(), mPreviewRect.height(), 100);
            return rect;

        } else {
            if (isFocusArea) {
                return calcTapAreaForCamera2(mPreviewRect.width() / 5, 1000);
            } else {
                return calcTapAreaForCamera2(mPreviewRect.width() / 4, 1000);
            }
        }

    }

    private Rect calcTapAreaForCamera2(int areaSize, int weight) {
        int left = clamp((int) currentX - areaSize / 2,
                mPreviewRect.left, mPreviewRect.right - areaSize);
        int top = clamp((int) currentY - areaSize / 2,
                mPreviewRect.top, mPreviewRect.bottom - areaSize);
        RectF rectF = new RectF(left, top, left + areaSize, top + areaSize);
        toFocusRect(mTransformer.toCameraSpace(rectF));
        return mFocusRect;
    }

    private int clamp(int x, int min, int max) {
        if (x > max) {
            return max;
        }
        if (x < min) {
            return min;
        }
        return x;
    }

    private RectF rectToRectF(Rect rect) {
        return new RectF(rect);
    }

    private void toFocusRect(RectF rectF) {
        mFocusRect.left = Math.round(rectF.left);
        mFocusRect.top = Math.round(rectF.top);
        mFocusRect.right = Math.round(rectF.right);
        mFocusRect.bottom = Math.round(rectF.bottom);
    }


    /**
     * 计算触摸区域
     *
     * @param x
     * @param y
     * @return
     */
    private Rect getFocusArea(float x, float y, int width, int height, int focusSize) {
        return calculateTapArea(x, y, width, height, focusSize, 1.0f);
    }

    /**
     * 计算点击区域
     *
     * @param x
     * @param y
     * @param width
     * @param height
     * @param focusSize
     * @param coefficient
     * @return
     */
    private Rect calculateTapArea(float x, float y, int width, int height,
                                  int focusSize, float coefficient) {
        int areaSize = Float.valueOf(focusSize * coefficient).intValue();
        int left = clamp(Float.valueOf((y / height) * 2000 - 1000).intValue(), areaSize);
        int top = clamp(Float.valueOf(((height - x) / width) * 2000 - 1000).intValue(), areaSize);
        return new Rect(left, top, left + areaSize, top + areaSize);
    }

    /**
     * 确保所选区域在在合理范围内
     *
     * @param touchCoordinateInCameraReper
     * @param focusAreaSize
     * @return
     */
    private int clamp(int touchCoordinateInCameraReper, int focusAreaSize) {
        int result;
        if (Math.abs(touchCoordinateInCameraReper) + focusAreaSize > 1000) {
            if (touchCoordinateInCameraReper > 0) {
                result = 1000 - focusAreaSize;
            } else {
                result = -1000 + focusAreaSize;
            }
        } else {
            result = touchCoordinateInCameraReper - focusAreaSize / 2;
        }
        return result;
    }


}
