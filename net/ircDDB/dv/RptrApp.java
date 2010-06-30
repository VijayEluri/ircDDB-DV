/*

ircDDB DV Plugins

Copyright (C) 2010   Michael Dirska, DL1BFF (dl1bff@mdx.de)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

*/

package net.ircDDB.dv;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;


import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Calendar;
import java.util.TimeZone;

import java.util.LinkedList;
import java.util.Properties;

import java.util.regex.Pattern;

import net.ircDDB.IRCApplication;
import net.ircDDB.IRCMessage;
import net.ircDDB.IRCMessageQueue;
import net.ircDDB.IRCDDBExtApp;



public class RptrApp implements IRCDDBExtApp
{

	Connection db;

	Statement  sql;

	IRCMessageQueue sendQ;

	String currentServerNick;

	String jdbcClass;
	String jdbcURL;
	String jdbcUsername;
	String jdbcPassword;

	int state;

	boolean fixTables;
	boolean fixUnsyncGIP;
	
	public void userJoin (String nick, String name, String host)
	{
		// System.out.println("IP UPDATE " + nick + " " + host);

		if (state < 2)
		{
			System.out.println("db not ready");
			return;
		}


		int i = nick.indexOf('-');

		if ((i > 3) && (i < 7)) // callsign with at 4,5 or 6 characters
		{
			String callsign = nick.substring(0,i).toUpperCase();
			
			while (callsign.length() < 8)
			{
				callsign = callsign + " ";
			}
			
			// System.out.println(callsign);


			try
			{
			    int r = sql.executeUpdate (
                                "update sync_gip set zonerp_ipaddr='" + host +
                                 "' where zonerp_cs='" + callsign + "'");

			    if ((r != 1) && fixTables)
			    {
			    	ResultSet rs = sql.executeQuery("select zonerp_cs from "+
					"sync_gip where zonerp_cs='" + callsign + "'");

				if ((rs != null) && !rs.next())
				{
					r = sql.executeUpdate ("insert into sync_gip values('" +
						callsign + "', now(), now(), now(), '" + host +
						"', false)");

					if (r != 1)
					{
					System.out.println("DBClient/insert sync_gip unexpected value "+r);
					}
				}
				else
				{
					System.out.println("DBClient/select zonerp_cs unexpected entry or rs==null");
				}

				if (rs!=null)
				{
					rs.close();
				}


				if (fixUnsyncGIP)
				{
			    	rs = sql.executeQuery("select zonerp_cs from "+
					"unsync_gip where zonerp_cs='" + callsign + "'");

				if ((rs != null) && !rs.next())
				{
					r = sql.executeUpdate ("insert into unsync_gip values('" +
						callsign + "', now(), now(), " +
						 "'1970-01-01 00:00:00', true, true)");

					if (r != 1)
					{
					System.out.println("DBClient/insert unsync_gip unexpected value "+r);
					}
				}
				else
				{
					System.out.println("DBClient/select zonerp_cs unexpected entry or rs==null");
				}

				if (rs!=null)
				{
					rs.close();
				}

				}

				
			    }
			}
			catch (SQLException e)
			{
				System.out.println("DBClient/executeQuery: " + e);
				state = 1;
				return;
			}
	
		}
	}

	public boolean needsDatabaseUpdate()
	{
		return true;
	}

	public Date getLastEntryDate()
	{
		if (state < 2)
		{
			System.out.println("wait for db");

			try
			{
				Thread.sleep(3000);
			}
			catch ( InterruptedException e )
			{
				System.out.println(e);
			}

			if (state < 2)
			{
				System.out.println("db not ready");
				return null;
			}
		}

		ResultSet rs;

		try
		{
			rs = sql.executeQuery(
				"select last_mod_time at time zone 'UTC' from sync_mng_external order by last_mod_time desc");

		}
		catch (SQLException e)
		{
			System.out.println("DBClient/executeQuery: " + e);
			state = 1;  // disconnect
			return null;
		}

		Date d = null;

		try
                {
                        if (rs != null)
                        {
				long n = 950000000000L;  // February 2000

                                if (rs.next())
                                {
                                        java.sql.Timestamp ts = rs.getTimestamp(1);
                                        n = ts.getTime();
					
				}

				d = new Date(n);

				rs.close();
			}
		}
		catch (SQLException e)
		{
			System.out.println("DBClient/ResultSet: " + e);
			state = 1;
			return null;
		}



		return d;
	}
	
	public IRCDDBExtApp.UpdateResult dbUpdate(Date d, String k, String v)
	{
		if (state < 2)
		{
			System.out.println("db not ready");
			return null;
		}
		
		ResultSet rs;

		String targetCS = k.replace('_', ' ');
                String areaCS = v.replace('_', ' ');

		String lastModTime = dbDateFormat.format(d);

		String oldAreaCS = "NONE";
		Date oldLastModTime = new Date(950000000000L);  // February 2000

		boolean insertNewEntry = false;

		try
                {
			String zoneCS = "NOCALL99";
			boolean doNotUpdate = false;

                        rs = sql.executeQuery(
                                "select last_mod_time at time zone 'UTC', arearp_cs from sync_mng_external where target_cs ='" + targetCS + "'");

			if (rs == null)
			{
				System.out.println("DBClient/ResultSet=null " );
				state = 1;
				return null;
			}

			if (rs.next())
			{
				oldLastModTime = new Date( rs.getTimestamp(1).getTime());
				oldAreaCS = rs.getString(2);

				insertNewEntry = false;

				if (oldLastModTime.getTime() > d.getTime())
	                        {
					doNotUpdate = true;
				}

			}
			else
			{
				insertNewEntry = true;
			}
			
			rs.close();

			Date nowDate = new Date();

			if (d.getTime() > (nowDate.getTime() + 300000))
			{
				doNotUpdate = true;
			}


			if (!doNotUpdate)
			{

			boolean targetIsAreaRP = false;

			rs = sql.executeQuery(
				"select zonerp_cs from sync_mng " +
				"where arearp_cs='" + targetCS + "' and del_flg=false limit 1"
				);

			if (rs == null)
			{
				System.out.println("DBClient/ResultSet=null " );
				state = 1;
				return null;
			}


			if (rs.next())
			{
				targetIsAreaRP = true;
			}

			rs.close();


			if (! targetIsAreaRP) // do the next steps only if targetCS is not an arearp_cs
			{
			rs = sql.executeQuery(
				"select zonerp_cs from sync_mng " +
				"where arearp_cs='" + areaCS + "' and del_flg=false limit 1"
				);

			if (rs == null)
			{
				System.out.println("DBClient/ResultSet=null " );
				state = 1;
				return null;
			}


			if (rs.next())
			{
				// System.out.println("zonerp = (" + rs.getString(1) + ")");
				zoneCS = rs.getString(1);
				rs.close();
			}
			else 
			{
				rs.close();

				if ((areaCS.charAt(6) == ' ') &&
					("ABC".indexOf (areaCS.charAt(7)) >= 0) &&
					  fixTables)
				{
					rs = sql.executeQuery("select target_cs from sync_mng " +
						"where target_cs = '" + areaCS + "'");

					if (rs == null)
					{
						System.out.println("DBClient/ResultSet=null " );
						state = 1;
						return null;
					}
	
					if (rs.next())
					{
						System.out.println("DBClient/select target_cs unexpected entry");
					}
					else
					{
						rs.close();

						String tmpZoneCS = areaCS.substring(0,6) + "  ";

                                                rs = sql.executeQuery(
                                                        "select zonerp_cs from sync_gip " +
                                                        "where zonerp_cs='" + tmpZoneCS + "'");

                                                if (rs == null)
                                                {
                                                        System.out.println("DBClient/ResultSet=null " );
                                                        state = 1;
                                                        return null;
                                                }


                                                if (rs.next()) // exists in sync_gip
                                                {

							int r = sql.executeUpdate( "insert into sync_mng values('" +
								areaCS + "', '" + lastModTime + "', now(), now(), '" +
								  tmpZoneCS.trim().toLowerCase() + "-module-" +
								  areaCS.substring(7,8).toLowerCase() + "', '" +
								  areaCS + "', '" + tmpZoneCS + "', '" +
								  tmpZoneCS + "', '" + tmpZoneCS + "', '0.0.0.0', false)");
							if (r != 1)
							{
								System.out.println("DBClient/insert2 unexpected value " + r);
							}

							rs.close();

							rs = sql.executeQuery(
								"select zonerp_cs from sync_mng " +
								"where arearp_cs='" + areaCS + "' and del_flg=false limit 1"
								);

							if (rs == null)
							{
								System.out.println("DBClient/ResultSet=null " );
								state = 1;
								return null;
							}


							if (rs.next())
							{
								// System.out.println("zonerp = (" + rs.getString(1) + ")");
								zoneCS = rs.getString(1);
							}
                                                }

					}

					rs.close();
				}
				

			}

			} // if (! targetIsAreaRP)



			if (insertNewEntry)
			{
				// System.out.println("insert");

				int r = sql.executeUpdate (
				"insert into sync_mng_external values('" + targetCS +
				  "', '" + lastModTime + "', '" + areaCS + "', '" +
				  zoneCS + "')" );
				if (r != 1)
				{
					System.out.println("DBClient/insert unexpected value " + r);
				}
			}
			else
			{
				// System.out.println("update all");

				int r = sql.executeUpdate (
				"update sync_mng_external set last_mod_time='" + lastModTime +
				 "', arearp_cs='" + areaCS + "', zonerp_cs='" + zoneCS +
				 "' where target_cs='" + targetCS + "'");

				if (r != 1)
				{
					System.out.println("DBClient/update1 unexpected value " + r);
				}
			}

			if (!zoneCS.equals("NOCALL99"))
			{
				sql.executeUpdate (
				"update sync_mng set arearp_cs='" + areaCS +
				 "', zonerp_cs='" + zoneCS +
				 "' where target_cs='" + targetCS + "'");
			}

			} // if (!doNotUpdate)

                }
                catch (SQLException e)
                {
                        System.out.println("DBClient/executeQuery: " + e);
                        state = 1;
                        return null;
                }

		IRCDDBExtApp.UpdateResult r = new IRCDDBExtApp.UpdateResult();
		IRCDDBExtApp.DatabaseObject n = new IRCDDBExtApp.DatabaseObject();
		IRCDDBExtApp.DatabaseObject o = new IRCDDBExtApp.DatabaseObject();

		r.keyWasNew = insertNewEntry;
		r.newObj = n;
		r.oldObj = o;

		n.modTime = d;
		n.key = k;
		n.value = v;

		while (oldAreaCS.length() < 8)
		{
			oldAreaCS = oldAreaCS + " ";
		}

		o.modTime = oldLastModTime;
		o.key = k;
		o.value = oldAreaCS.replace(' ', '_');
		
		return r;
	}
	
	public LinkedList<IRCDDBExtApp.DatabaseObject> getDatabaseObjects(Date d, int num)
	{
		return null;
	}
	
    	public void setParams (Properties p, Pattern k, Pattern v)
	{
		jdbcClass = p.getProperty("jdbc_class", "none");
		jdbcURL = p.getProperty("jdbc_url", "none");
		jdbcUsername = p.getProperty("jdbc_username", "none");
		jdbcPassword = p.getProperty("jdbc_password", "none");


		fixTables = p.getProperty("rptr_fix_tables", "no").equals("yes");
		fixUnsyncGIP = p.getProperty("rptr_fix_unsync_gip", "yes").equals("yes");

		if (fixTables)
		{
			System.out.println("Missing repeater entries in 'sync_mng' and 'sync_gip' "+
				"will be created automatically.");

			if (fixUnsyncGIP)
			{
			System.out.println("Missing repeater entries in 'unsync_gip' "+
					"will be created automatically.");
			}	
			else
			{
				System.out.println("The table 'unsync_gip' will not be changed.");
			}
		}
	}

    	public void setTopic (String topic)
	{
	}
	
    	public void setCurrentNick (String nick)
	{
	}
	
    	public void setCurrentServerNick (String nick)
	{
		currentServerNick = nick;
	}
	
    	public void userListReset ()
	{
	}
	

	public void userLeave (String nick)
	{
	}
	
	public void userChanOp (String nick, boolean op)
	{
	}

	public void msgChannel (IRCMessage m)
	{
	}
	
	public void msgQuery (IRCMessage m)
	{
	}

	public synchronized IRCMessageQueue getSendQ ()
	{
		return sendQ;
	}


	public synchronized void setSendQ (IRCMessageQueue q)
	{
		sendQ = q;
	}


	SimpleDateFormat dbDateFormat;

	public RptrApp()
	{
		db = null;
		sql = null;

		sendQ = null;
		currentServerNick = null;

		state = 0;

		dbDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                dbDateFormat.setTimeZone( TimeZone.getTimeZone("GMT"));

		fixTables = false;
		fixUnsyncGIP = false;
	}	

	boolean init()
	{
		DatabaseMetaData dbmd; 

		try
		{
			Class.forName(jdbcClass);
		}
		catch (ClassNotFoundException e)
		{
			System.out.println("DBClient/ClassLoader: " + e);
			return false;
		}

		try
		{
			db = DriverManager.getConnection(jdbcURL,
				jdbcUsername, jdbcPassword);
		}
		catch (SQLException e)
		{
			System.out.println("DBClient/getConnection: " + e);
			return false;
		}

		try
		{
			dbmd = db.getMetaData();
		}
		catch (SQLException e)
		{
			System.out.println("DBClient/getMetaData: " + e);
			return false;
		}

		try
		{
			sql = db.createStatement();
		}
		catch (SQLException e)
		{
			System.out.println("DBClient/createStatement: " + e);
			return false;
		}


		return true;
	}


	void closeConnection()
	{
		if (db != null)
		{
			try
			{
				db.close();
			}
			catch (SQLException e)
			{
				System.out.println("DBClient/close: " + e);
			}
	
		}

		db = null;
		sql = null;
	}

	public void run()
	{
		int timer = 0;


		while(true)
		{
		    if (timer == 0)
		    {
			switch(state)
			{
			case 0:
				System.out.println("DBClient: connect request");
				if (init())
				{
					System.out.println("DBClient: connected");
					state = 2;
				}
				else
				{
					state = 1;
				}
				break;

			case 1:
				closeConnection();
				timer = 5;
				state = 0;
				break;

				// state > 2  ->  operational
			case 2:
				if (sendQ != null) // wait for sendQ
				{
					state = 3;
				}	
				break;
				
			case 3:
				if (sendQ == null)
				{
					state = 1;
				}

				break;

			}



		    }
		    else
		    {
			timer--;
		    }

			try
			{
				Thread.sleep(1000);
			}
			catch ( InterruptedException e )
			{
				System.out.println(e);
			}
		}

	}	
}

