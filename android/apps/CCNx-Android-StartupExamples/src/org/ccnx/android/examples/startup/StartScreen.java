package org.ccnx.android.examples.startup;


import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

public class StartScreen extends Activity implements OnClickListener {
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
		Button button;
		
		button = (Button) findViewById(R.id.btnStartBlocking);
		button.setOnClickListener(this);

		button = (Button) findViewById(R.id.btnStartNonBlocking);
		button.setOnClickListener(this);
    }
    
	public void onClick(View v) {
		// do something when the button is clicked

		Log.d("StartScreen", "OnClickListener " + String.valueOf(v.getId()));

		switch (v.getId()) {
		case R.id.btnStartBlocking:
			startActivity(new Intent(this, BlockingStartup.class));
			break;

		case R.id.btnStartNonBlocking:
			startActivity(new Intent(this, NonBlockingStartup.class));
			break;
			
		default:
			break;
		}
	}
}