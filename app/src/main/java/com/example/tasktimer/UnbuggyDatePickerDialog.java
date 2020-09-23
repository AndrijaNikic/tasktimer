package com.example.tasktimer;

import android.app.DatePickerDialog;
import android.content.Context;

// Replacing tryNotifyDateSet() with nothing - this is a workaround for Android bug in API 4.x
// see <a href="https://issuetracker.google.com/issues/36951008"&gt;https://issuetracker.google.com/issues/36951008&lt;/a>
// Fix by Wojtek Jarosz

public class UnbuggyDatePickerDialog extends DatePickerDialog {

    public UnbuggyDatePickerDialog(Context context, DatePickerDialog.OnDateSetListener callBack, int year, int monthOfYear, int dayOfMonth) {
        super(context, callBack, year, monthOfYear, dayOfMonth);
    }

    @Override
    protected void onStop() {
        // do nothing - do not call super method
    }
}
