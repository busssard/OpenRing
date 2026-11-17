package app.openring.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import app.openring.core.Grid;

/**
 * Cells of one size in rows (owner's wish of 17 November: badges of a consistent size, on Today and on the Badges page): each
 * cell gets the same width, and all get the height of the tallest one. The sizes come from {@code core/Grid}.
 */
final class EqualGrid extends ViewGroup {
    private final int columns, gap;
    private int cellW, cellH;

    EqualGrid(Context c, int columns, int gapDp) {
        super(c);
        this.columns = Math.max(1, columns);
        this.gap = Ui.dp(c, gapDp);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        cellW = Grid.cellWidth(width, columns, gap);
        int n = getChildCount();
        int[] heights = new int[n];
        int wSpec = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY);
        for (int i = 0; i < n; i++) {
            View v = getChildAt(i);
            v.measure(wSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            heights[i] = v.getMeasuredHeight();
        }
        cellH = Grid.cellHeight(heights);
        int hSpec = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY);
        for (int i = 0; i < n; i++) getChildAt(i).measure(wSpec, hSpec);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Grid.height(n, columns, cellH, gap) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            int x = getPaddingLeft() + Grid.left(i, columns, cellW, gap), y = getPaddingTop() + Grid.top(i, columns, cellH, gap);
            getChildAt(i).layout(x, y, x + cellW, y + cellH);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
