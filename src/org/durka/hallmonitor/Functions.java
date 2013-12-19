/* Copyright 2013 Alex Burka

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.durka.hallmonitor;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.admin.DevicePolicyManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Container Class for various inner Classes which service the capabilities of the HallMonitor app.
 */
public class Functions {
	
	public static final int DEVICE_ADMIN_WAITING = 42;
	
	//needed for the call backs from the widget picker
	//pick is the call back after picking a widget, configure is the call back after
	//widget configuration
	public static final int REQUEST_PICK_APPWIDGET = 9;
	public static final int REQUEST_CONFIGURE_APPWIDGET = 5;
	
	//Class that handles interaction with 3rd party App Widgets
	public static final HMAppWidgetManager hmAppWidgetManager = new HMAppWidgetManager();
	
	public static DefaultActivity defaultActivity;
	public static Configuration configurationActivity;
	
	/**
	 * Provides methods for performing actions. (e.g. what to do when the cover is opened and closed etc.)
	 */
	public static class Actions {
		
		//used for the timer to turn off the screen on a delay
        private static Timer timer = new Timer();
        private static TimerTask timerTask;
		
        
        /**
         * Called whenever the cover_closed event is called. Also called from DefaultActivity when the 
         * screen turns on whilst the cover is closed.
         * Switches to the default activity screen and if we are running root enabled boosts the screen sensitivity.
         * After pref_delay milliseconds locks the screen.
         * @param ctx Application context.
         */
		public static void close_cover(Context ctx) {
			
			Log.d("F.Act.close_cover", "Close cover event receieved.");
			
			//save the cover state
			Events.set_cover(true);
			
			
		    // step 1: bring up the default activity window
			//we are using the show when locked flag as we'll re-use this method to show the screen on power button press
			if (!DefaultActivity.on_screen) {
				ctx.startActivity(new Intent(ctx, DefaultActivity.class)
										.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
												| Intent.FLAG_ACTIVITY_NO_ANIMATION
												| WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED));
			}
            
			
			//if we are running in root enabled mode then lets up the sensitivity on the view screen
			//so we can use the screen through the window
			 if (Functions.Events.rootEnabled) {
				 Log.d("F.Act.close_cover", "We're root enabled so lets boost the sensitivity...");
				 run_commands_as_root(new String[]{"cd /sys/class/sec/tsp", "echo clear_cover_mode,1 > cmd"});
				 Log.d("F.Act.close_cover", "...Sensitivity boosted, hold onto your hats!");
			 }
			
			//need this to let us lock the phone
			final DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
			//final PowerManager pm = (PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
			
			ComponentName me = new ComponentName(ctx, AdminReceiver.class);
			if (!dpm.isAdminActive(me)) {
				// if we're not an admin, we can't do anything
				Log.d("F.Act.close_cover", "We are not an admin so cannot do anything.");
				return;
			}
			
			
			
            //step 2: wait for the delay period and turn the screen off
            int delay = PreferenceManager.getDefaultSharedPreferences(ctx).getInt("pref_delay", 10000);
            
            Log.d("F.Act.close_cover", "Delay set to: " + delay);
            
            
            //using the handler is causing a problem, seems to lock up the app, hence replaced with a Timer
            timer.schedule(timerTask = new TimerTask() {
			//handler.postDelayed(new Runnable() {
				@Override
				public void run() {	
					Log.d("F.Act.close_cover", "Locking screen now.");
					dpm.lockNow();
					//FIXME Would it be better to turn the screen off rather than actually locking
					//presumably then it will auto lock as per phone configuration
					//I can't work out how to do it though!
				}
			}, delay);
            
		}
		
		
		/**
		 * Called from within the Functions.Event.Proximity method.
         * If we are running root enabled reverts the screen sensitivity.
         * Wakes the screen up.
         * @param ctx Application context.
		 */
		@SuppressWarnings("deprecation")
		public static void open_cover(Context ctx) {
			
			Log.d("F.Act.open_cover", "Open cover event receieved.");
			
			//we don't want the configuration screen displaying when we wake back up
			if (configurationActivity != null) configurationActivity.moveTaskToBack(true);
	        //we also don't want to see the default activity
	        if (defaultActivity != null)  defaultActivity.moveTaskToBack(true);
	        
			//needed to let us wake the screen
			PowerManager pm  = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
			
			// step 1: if we were going to turn the screen off, cancel that
			if (timerTask != null) timerTask.cancel();
			
			// step 2: wake the screen
			//FIXME Would be nice to remove the deprecated FULL_WAKE_LOCK if possible
			PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, ctx.getString(R.string.app_name));
	        wl.acquire();
	        wl.release();
			
			//save the cover state
			Events.set_cover(false);
			
			//if we are running in root enabled mode then lets revert the sensitivity on the view screen
			//so we can use the device as normal
			 if (Functions.Events.rootEnabled) {
				 Log.d("F.Act.close_cover", "We're root enabled so lets revert the sensitivity...");
				 run_commands_as_root(new String[]{"cd /sys/class/sec/tsp", "echo clear_cover_mode,0 > cmd"});
				 Log.d("F.Act.close_cover", "...Sensitivity reverted, sanity is restored!");
			 }
		}

		
		/**
		 * Starts the HallMonitor service. Service state is dependent on admin permissions.
		 * This requests admin permissions. The onActionReceiver will pick that up 
		 * and do the necessary to start the service. 
		 * @param act Activity context.
		 */
		public static void start_service(Activity act) {
			Log.d("F.Act.start_service", "Start service called.");
			// Become device admin
			DevicePolicyManager dpm = (DevicePolicyManager) act.getSystemService(Context.DEVICE_POLICY_SERVICE);
			ComponentName me = new ComponentName(act, AdminReceiver.class);
			if (!dpm.isAdminActive(me)) {
				Intent coup = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
				coup.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, me);
				coup.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, act.getString(R.string.admin_excuse));
				act.startActivityForResult(coup, DEVICE_ADMIN_WAITING);
			}
		}
		
		/**
		 * Stops the HallMonitor service.
		 * @param ctx Application context.
		 */
		public static void stop_service(Context ctx) {
			
			Log.d("F.Act.stop_service", "Stop service called.");
			
			ctx.stopService(new Intent(ctx, ViewCoverService.class));
			ctx.stopService(new Intent(ctx, NotificationService.class));
			
			// Relinquish device admin
			DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
			ComponentName me = new ComponentName(ctx, AdminReceiver.class);
			if (dpm.isAdminActive(me)) dpm.removeActiveAdmin(me);
		}
		
		
		/**
		 * Hand off to the HMAppWidgetManager to deal with registering new app widget.
		 * @param act The Activity to use as the context for these actions
		 * @param widgetType The type of widget (e.g. 'default', 'media', 'notification' etc.)
		 */
		public static void register_widget(Activity act, String widgetType) {
			
			Log.d("F.Act.register_widget", "Register widget called for type: " + widgetType);
			//hand off to the HM App Widget Manager for processing
			hmAppWidgetManager.register_widget(act, widgetType);
		}
		
		/**
		 * Hand off to the HMAppWidgetManager to deal with unregistering existing app widget.
		 * @param act The Activity to use as the context for these actions
		 * @param widgetType The type of widget (e.g. 'default', 'media', 'notification' etc.)
		 */
		public static void unregister_widget(Activity act, String widgetType) {
			
			Log.d("F.Act.unregister_widget", "unregister widget called for type: " + widgetType);
			//hand off to the HM App Widget Manager for processing
			hmAppWidgetManager.unregister_widget(act, widgetType);
		}
		
		
		/**
		 * Execute shell commands
		 * @param cmds Commands to execute
		 */
		public static void run_commands_as_root(String[] cmds) { run_commands_as_root(cmds, true); }
		
		public static void run_commands_as_root(String[] cmds, boolean want_output) {
	        try {
	        	Process p = Runtime.getRuntime().exec("su");
	        	
	        	//create output stream for running commands
	            DataOutputStream os = new DataOutputStream(p.getOutputStream());  
	            
	            //tap into the output
	            InputStream is = p.getInputStream();
	            BufferedReader isBr = new BufferedReader(new InputStreamReader(is));
	            
	            //tap into the error output
	            InputStream es = p.getErrorStream();
	            BufferedReader esBr = new BufferedReader(new InputStreamReader(es));
	            
	            //use this for collating the output
	            String currentLine;
	            
	            //run commands
	            for (String tmpCmd : cmds) {
	            	Log.d("F.Act.run_comm_as_root", "Running command: " + tmpCmd);
                    os.writeBytes(tmpCmd+"\n");
	            }      
	            os.writeBytes("exit\n");  
	            os.flush();
	            
	            if (want_output) {
		            //log out the output
		            String output = "";
		            while ((currentLine = isBr.readLine()) != null) {
		              output += currentLine + "\n";
		            } 
		            Log.d("F.Act.run_comm_as_root", "Have output: " + output);
	           
		            //log out the error output
		            String error = "";
		            currentLine = "";
		            while ((currentLine = esBr.readLine()) != null) {
		              error += currentLine + "\n";
		            }	           
		            Log.d("F.Act.run_comm_as_root", "Have error: " + error);
	            }

	        } catch (IOException ioe) {
	        	Log.e("F.Act.run_comm_as_root","Failed to run command!", ioe);
	        }
		}


		public static void hangup_call() {
			Log.d("phone", "hanging up! goodbye");
			run_commands_as_root(new String[]{"input keyevent 6"}, false);
			DefaultActivity.phone_ringing = false;
			defaultActivity.refreshDisplay();
		}
		
		public static void pickup_call() {
			Log.d("phone", "picking up! hello");
			run_commands_as_root(new String[]{"input keyevent 5"}, false);
			//DefaultActivity.phone_ringing = false;
			//defaultActivity.refreshDisplay();
		}
	}

	
	/**
	 * Provides event handling.
	 */
	public static class Events {
		
		//is the cover closed
		private static boolean cover_closed;
		//has root been enabled
		public static boolean rootEnabled = false;
		//have alarm controls been enabled
		public static boolean alarmControlsEnabled = false;
		
		/**
		 * Invoked from the BootReceiver, allows for start on boot, as is registered in the manifest as listening for:
		 * android.intent.action.BOOT_COMPLETED and
		 * android:name="android.intent.action.QUICKBOOT_POWERON" 
		 * Starts the ViewCoverService which handles detection of the cover state.
		 * @param ctx Application context
		 */
		public static void boot(Context ctx) {
			
			Log.d("F.Evt.boot", "Boot called.");
			
			if (PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("pref_enabled", false)) {
	    		Intent startServiceIntent = new Intent(ctx, ViewCoverService.class);
	    		ctx.startService(startServiceIntent);
	    	}
		}
		
		
		/**
		 * The Configuration activity acts as the main activity for the app. Any events received into
		 * its onActivityReceived method are passed on to be handled here.
		 * @param ctx Application context.
		 * @param request Request Activity ID.
		 * @param result Result Activity ID.
		 * @param data Intent that holds the data received.
		 */
		public static void activity_result(Context ctx, int request, int result, Intent data) {
			Log.d("F.Evt.activity_result", "Activity result received: request=" + Integer.toString(request) + ", result=" + Integer.toString(result));
			switch (request) {
			  //call back for admin access request
			  case DEVICE_ADMIN_WAITING:
				if (result == Activity.RESULT_OK) {
					// we asked to be an admin and the user clicked Activate
					// (the intent receiver takes care of showing a toast)
					// go ahead and start the service
					ctx.startService(new Intent(ctx, ViewCoverService.class));
				} else {
					// we asked to be an admin and the user clicked Cancel (why?)
					// complain, and un-check pref_enabled
					Toast.makeText(ctx, ctx.getString(R.string.admin_refused), Toast.LENGTH_SHORT).show();
					Log.d("F.Evt.activity_result", "pref_enabled = " + Boolean.toString(PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("pref_enabled", true)));
					PreferenceManager.getDefaultSharedPreferences(ctx)
							.edit()
							.putBoolean("pref_enabled", false)
							.commit();
					Log.d("F.Evt.activity_result", "pref_enabled = " + Boolean.toString(PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("pref_enabled", true)));
				}
				break;
			  //call back for appwidget pick	
			  case REQUEST_PICK_APPWIDGET:
				//widget picked
				if (result == Activity.RESULT_OK) {
					//widget chosen so launch configurator
					hmAppWidgetManager.configureWidget(data, ctx);
				} else {
					//choose dialog cancelled so clean up
					int appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
			        if (appWidgetId != -1) {
			        	hmAppWidgetManager.deleteAppWidgetId(appWidgetId);
					}
				}
				break;		
			  //call back for appwidget configure
			  case REQUEST_CONFIGURE_APPWIDGET:
				//widget configured
				if (result == Activity.RESULT_OK) {
					//widget configured successfully so create it
					hmAppWidgetManager.createWidget(data, ctx);
				} else {
					//configure dialog cancelled so clean up
					int appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
			        if (appWidgetId != -1) {
			        	hmAppWidgetManager.deleteAppWidgetId(appWidgetId);
					}
				break;			
			    }
			}
		}
		
		
		/**
		 * Invoked via the AdminReceiver when the admin status changes.
		 * @param ctx Application context.
		 * @param admin Is the admin permission granted.
		 */
		public static void device_admin_status(Context ctx, boolean admin) {
			
			Log.d("F.Evt.dev_adm_status", "Device admin status called with admin status: " + admin);
			
			Toast.makeText(ctx, ctx.getString(admin ? R.string.admin_granted : R.string.admin_revoked), Toast.LENGTH_SHORT).show();
			
			//FIXME none of the below seems to actually be necessary?
			/*if (admin) {
				if (PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("pref_enabled", false) && Is.cover_closed(ctx)) {
					Actions.close_cover(ctx);
				}
			} else {
				Actions.stop_service(ctx);
				PreferenceManager.getDefaultSharedPreferences(ctx)
						.edit()
						.putBoolean("pref_enabled", false)
						.commit();
			}*/
			
			
		}
		
		/**
		 * Setter method for the cover closed state.
		 * @param closed Is the cover closed
		 */
		public static void set_cover(boolean closed) {
			cover_closed = closed;
		}
		
		
		/**
		 * Receives the value of the proximity sensor and reacts accordingly to update the cover state.
		 * @param ctx Application context.
		 * @param value Value of the proximity sensor.
		 */
		public static void proximity(Context ctx, float value) {
			
			Log.d("F.Evt.proximity", "Proximity method called with value: " + value + " ,whilst cover_closed is: " + cover_closed);
			
			if (value > 0) {
				if (cover_closed) {
					if (!Functions.Is.cover_closed(ctx)) {
						//proximity false (>0) and cover open - take open_cover action
						Actions.open_cover(ctx);
					}
				}
			} else {
				if (!cover_closed) {
					if (Functions.Is.cover_closed(ctx)) {
						//proximity true (<=0) and cover closed - take close_cover action
						Actions.close_cover(ctx);
					}
				}
			}
			//Log.d(ctx.getString(R.string.app_name), String.format("cover_closed = %b", cover_closed));
		}


		public static void incoming_call(final Context ctx, String number) {
			Log.d("phone", "call from " + number);
			if (Functions.Is.cover_closed(ctx)) {
				Log.d("phone", "but the screen is closed. screen my calls");
				
				//if the cover is closed then
				//we want to pop this activity up over the top of the dialer activity
				//to guarantee that we need to hold off until the dialer activity is running
				//a 1 second delay seems to allow this
				DefaultActivity.phone_ringing = true;
				DefaultActivity.call_from = number;

				Timer timer = new Timer();
				timer.schedule(new TimerTask() {
					@Override
					public void run() {
						Intent intent = new Intent(ctx, DefaultActivity.class);
						intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
							          | Intent.FLAG_ACTIVITY_CLEAR_TOP
							          | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
						intent.setAction(Intent.ACTION_MAIN);
						ctx.startActivity(intent);

					}
				}, 1000);
				
				/*
				new Handler().postDelayed(new Runnable() {
					@Override
					public void run() {
						Process process;
						try {
							process = Runtime.getRuntime().exec(new String[]{ "su","-c","input keyevent 6"});
							process.waitFor();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					    
					}
				}, 500);
				*/
				
			}
		}
		
		public static void call_finished(Context ctx) {
			Log.d("phone", "call is over, cleaning up");
			DefaultActivity.phone_ringing = false;
			((TextView)defaultActivity.findViewById(R.id.call_from)).setText(ctx.getString(R.string.unknown_caller));
		}
	}
	
	/**
	 * Contains methods to check the state
	 */
	public static class Is {
		
		/**
		 * Is the cover closed.
		 * @param ctx Application context.
		 * @return Is the cover closed.
		 */
		public static boolean cover_closed(Context ctx) {
			
			Log.d("F.Is.cover_closed", "Is cover closed called.");
			
			String status = "";
			try {
				Scanner sc = new Scanner(new File(ctx.getString(R.string.hall_file)));
				status = sc.nextLine();
				sc.close();
			} catch (FileNotFoundException e) {
				Log.e(ctx.getString(R.string.app_name), "Hall effect sensor device file not found!");
			}
			
			boolean isClosed = (status.compareTo("CLOSE") == 0);
			
			Log.d("F.Is.cover_closed","Cover closed state is: " + true);
			
			return isClosed;
		}
		
		
		/**
		 * Is the service running.
		 * @param ctx Application context.
		 * @return Is the cover closed.
		 */
		public static boolean service_running(Context ctx) {
			
			Log.d("F.Is.service_running", "Is service running called.");
			
			ActivityManager manager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
				if (ViewCoverService.class.getName().equals(service.service.getClassName())) {
					// the service is running
					Log.d("F.Is.service_running", "The service is running.");
					return true;
				}
			}
			// the service must not be running
			Log.d("F.Is.service_running", "The service is NOT running.");
			return false;
		}
		
		
		/**
		 * Is the specified widget enabled
		 * @param ctx Application context
		 * @param widgetType Widget type to check for
		 * @return True if it is, False if not
		 */
		public static boolean widget_enabled(Context ctx, String widgetType) {
			
			Log.d("F.Is.wid_enabled", "Is default widget enabled called with widgetType: " + widgetType);
			
			boolean widgetEnabled = Functions.hmAppWidgetManager.doesWidgetExist(widgetType);
			
			Log.d("F.Is.wid_enabled", widgetType + " widget enabled state is: " + widgetEnabled);
			
			return widgetEnabled;
		}
		
		
		
		
		
	}
	
	public static class Util {
		// from http://stackoverflow.com/questions/3712112/search-contact-by-phone-number
		public static String getContactName(Context ctx, String number) {
			Log.d("phone", "looking up " + number + "...");
			
		    Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
		    String name = number;

		    ContentResolver contentResolver = ctx.getContentResolver();
		    Cursor contactLookup = contentResolver.query(uri, new String[] {BaseColumns._ID,
		            ContactsContract.PhoneLookup.DISPLAY_NAME }, null, null, null);

		    try {
		        if (contactLookup != null && contactLookup.getCount() > 0) {
		            contactLookup.moveToNext();
		            name = contactLookup.getString(contactLookup.getColumnIndex(ContactsContract.Data.DISPLAY_NAME));
		            //String contactId = contactLookup.getString(contactLookup.getColumnIndex(BaseColumns._ID));
		        }
		    } finally {
		        if (contactLookup != null) {
		            contactLookup.close();
		        }
		    }

		    Log.d("phone", "...result is " + name);
		    return name;
		}
	}

}
