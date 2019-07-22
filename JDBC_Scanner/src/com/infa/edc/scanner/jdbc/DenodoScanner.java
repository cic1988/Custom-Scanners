package com.infa.edc.scanner.jdbc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.sql.Connection;

import com.opencsv.CSVWriter;

public class DenodoScanner extends GenericScanner {
	public static final String version="0.9.7";
	
	protected static String DISCLAIMER="\n************************************ Disclaimer *************************************\n"
			 + "By using the Denodo scanner, you are agreeing to the following:-\n"
			 + "- this custom scanner is not officially supported by Informatica\n"
			 + "  it is a work in progress based on use-cases/examples we have seen so far\n"
			 + "- there are some limitations for external linking/lineage for non-relational data sources.\n"
			 + "  external references (Custom Lineage) is only generated for JDBC sources in the current version\n"
			 + "- Issues can be created on githib:- \n"
			 + "  https://github.com/Informatica-EIC/Custom-Scanners  (JDBC_Scanner folder)\n"
			 + "*************************************************************************************\n"
			 + "\n";


	protected String databaseName=""; 
	
	protected CSVWriter custLineageWriter = null; 
	protected PrintWriter debugWriter = null;
	
	protected int expressionsFound = 0;
	protected int expressionsProcessed = 0;
	protected int expressionLinks = 0;
	protected int totalColumnLineage = 0;
	protected int totalTableLineage = 0;
	
	protected Set<String> datasetsScanned = new HashSet<String>();
	protected Set<String> elementsScanned = new HashSet<String>();

	
	protected Map<String, List<String>> viewDbNameMap = new HashMap<String, List<String>>();
	protected Map<String, List<String>> tableDbNameMap = new HashMap<String, List<String>>();
	protected Map<String, Map<String, List<String>>> colDbNameMap = new HashMap<String, Map<String, List<String>>>();
	
	protected Map<String, String> tableWrapperTypes = new HashMap<String,String>();
	
	// debugging for AxaXL
	protected Set<String> epSkipViews = new HashSet<String>();
	
	protected boolean doDebug = false;
	protected boolean exportCustLineageInScanner = false;

	public DenodoScanner(String propertyFile) {
		super(propertyFile);
		
		// denodo specific settings read here (default settings in generic superclass)
        System.out.println(this.getClass().getSimpleName() + " " + version +  " initializing properties from: " + propertyFile);
		
		// store the property file
		propertyFileName = propertyFile;
		
		try {
			File file = new File(propertyFile);
			FileInputStream fileInput = new FileInputStream(file);
			Properties prop;
			prop = new Properties();
			prop.load(fileInput);
			fileInput.close();
			
			databaseName = prop.getProperty("denodo.databaseName", "denodo_vdp");
			if (databaseName == null || databaseName.equals("")) {
				System.out.println("empty value set for denodo.databaseName: using 'denodo_vdp'");
				databaseName = "denodo_vdp";
			}
			
			System.out.println("Database name used for Denodo export:" + databaseName);
			
			doDebug = Boolean.parseBoolean(prop.getProperty("debug", "false"));
			System.out.println("debug mode=" + doDebug);
			
			exportCustLineageInScanner = Boolean.parseBoolean(prop.getProperty("include.custlineage", "false"));
			System.out.println("export custom lineage in scanner zip=" + exportCustLineageInScanner + " 10.2.2hf1+ feature");
			
			// look for any tables to skip in ep.skipTables
			String epSkip = prop.getProperty("ep.skipobjects", "");
			if (epSkip != null && epSkip.length() > 0) {
				String[] elements =  epSkip.split(",");
				System.out.println("processing ep.skipobjects=" + epSkip);
//				System.out.println("filter conditions for catalog " + catalogs);
				for (String filteredView: elements) {
					epSkipViews.add(filteredView.trim());
					
				}
				System.out.println("skipping extra processing for: " + epSkipViews);
			}
			
			
						
	     } catch(Exception e) {
	     	System.out.println("error reading properties file: " + propertyFile);
	     	e.printStackTrace();
	     }

	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length==0) {
			System.out.println("Denodo Custom scanner for EDC: missing configuration properties file: usage:  DenodoScanner <folder>/<config file>.properties");
			System.exit(0);
		}

		System.out.println("Denodo Custom scanner: " + args[0] + " currentTimeMillis=" +System.currentTimeMillis());

		// check to see if a disclaimer override parameter was passed
		String disclaimerParm="";
		if (args.length>=2) {
			// 2nd argument is an "agreeToDisclaimer" string
			disclaimerParm=args[1];
//			System.out.println("disclaimer parameter passed: " + disclaimerParm);
			if ("agreeToDisclaimer".equalsIgnoreCase(disclaimerParm)) {
				System.out.println("the following disclaimer was agreed to by passing 'agreeToDisclaimer' as 2nd parameter");
				System.out.println(DISCLAIMER);
			}
		}
				
		// 1 of 2 conditions must be true for the scanner to start
		// 1 - 2nd parameter is equal to "agreeToDisclaimer"
		// or (if no parm passed, or value does not match)
		// then the user must agree to the prompt
		if ("agreeToDisclaimer".equalsIgnoreCase(disclaimerParm) || showDisclaimer()) {
			DenodoScanner scanner = new DenodoScanner(args[0]);
			scanner.run();
		} else {
			System.out.println("Disclaimer was declined - exiting");
		}

		

	}
	
	/**
	 * iterate over all catalogs (databases) there may be multiple
	 * need to determine whether to default to extracting all, or only a subset
	 */
	@Override
	public void getCatalogs() {
		System.out.println("Step 5: creating denodo catalog...");
		
        this.createDatabase(databaseName);
        
        getSchemas(databaseName);
	}
	

	/**
	 * get the schemas denodo - which are really catalogs
	 * @param catalogName
	 */
	@Override
	public void getSchemas(String catalogName) {
		System.out.println("Step 6: creating denodo schemas (from catalogs)");
		ResultSet catalogs;
		try {
			catalogs = dbMetaData.getCatalogs();
			String schemaName;
		    while (catalogs.next()) {
		    	schemaName = catalogs.getString(1);  //"TABLE_CATALOG"
		        System.out.println("\tschema: " + schemaName);
		        
				if (isCatalogScanned(schemaName)) {
			        // create the schema object
		    		createSchema(catalogName, schemaName);
		    		// process tables
		    		getTables(catalogName, schemaName);
		    		getViews(catalogName, schemaName);
		    		
		    		// call flush for each schema (helps with monitoring)
		    		this.flushFiles();
				} else {
					// message for catalog is not exported...
					System.out.println("\tschema=" + schemaName + " skipped - not included in catalog filter: " + catalogFilter);
				}

		        

		    }
		} catch (SQLException e) {
			e.printStackTrace();
		}

	}

	
	/** 
	 * find all table objects
	 * @param catalogName
	 * @param schemaName
	 */
	protected void getTables(String catalogName, String schemaName) {
		if (doDebug && debugWriter !=null) {
			debugWriter.println("entering getTables(" +catalogName+ ", " + schemaName + ")" );
			debugWriter.flush();
		}
		try {
			if (doDebug && debugWriter !=null) {
				debugWriter.println("calling (dbMetaData.getTables(" + schemaName + ", null, null, new String[] { \"TABLE\" })" );
				debugWriter.flush();
			}
			ResultSet rsTables = dbMetaData.getTables(schemaName, null, null, new String[] { "TABLE" });
			int tableCount = 0;
			while (rsTables.next()) {
				// Print
				tableCount++;
				
				String tableName=rsTables.getString("TABLE_NAME");
				
				List<String> values = tableDbNameMap.get(schemaName);
				if (values==null) {
					values = new ArrayList<String>();
				}
				values.add(tableName);
				tableDbNameMap.put(schemaName, values);
				datasetsScanned.add(schemaName + "/" + tableName);
				
				// get the wrapper type for the table (could be FF, JDBC, WS and some others)
				// this is important for the external lineage for the objects...
				String wrapperType = extractWrapperType(schemaName, tableName);


				// System.out.println("found one...");
				System.out.print("\t\t" + rsTables.getString("TABLE_CAT") //+ " schema="
						//+ rsTables.getString("TABLE_SCHEM") 
						+ "." + rsTables.getString("TABLE_NAME")
//						+ " comments=" + rsTables.getClob("REMARKS") 
						+ " wrapperType=" + wrapperType
						);
				if (doDebug && debugWriter !=null) {
					debugWriter.println("getTables\t" + " catalog=" + rsTables.getString("TABLE_CAT") + " schema="
							+ rsTables.getString("TABLE_SCHEM") + " tablename=" + rsTables.getString("TABLE_NAME")
							+ " TABLE_TYPE=" + rsTables.getString("TABLE_TYPE")
//							+ " comments=" + rsTables.getClob("REMARKS")
							);
				}

//				System.out.println(rsTables.getMetaData().getColumnTypeName(5));
				this.createTable(catalogName, schemaName, rsTables.getString("TABLE_NAME"), rsTables.getString("REMARKS"));
				
				
				this.getColumnsForTable(catalogName, schemaName, rsTables.getString("TABLE_NAME"), false);
			}
			System.out.println("\tTables extracted: " + tableCount);
//			System.out.println(this.tableWrapperTypes);

		} catch (SQLException e) {
			e.printStackTrace();
		}

		if (doDebug && debugWriter !=null) {
			debugWriter.println("exiting getTables(" +catalogName+ ", " + schemaName + ")" );
			debugWriter.flush();
		}


	}
	
	protected Map<String,String> columnExpressions = new HashMap<String,String>();
	
	protected void storeTableColExpressions(String schemaName, String tableName) {
//		System.out.println("gathering expression fields for table" + schemaName + "/" + tableName);
		if (doDebug && debugWriter !=null) {
			debugWriter.println("entering storeTableColExpressions(" +schemaName+ ", " + tableName + ")" );
			debugWriter.flush();
		}
		
		String selectSt = "select input_view_database_name, input_view_name, column_name, expression "
				+ "from  COLUMN_DEPENDENCIES ('" 
				+ schemaName + "', '"
				+ tableName +"', null)"
				+ "where depth=1 and input_view_name='"
				+ tableName +"' "
				+ "and expression is not null";
		if (doDebug && debugWriter !=null) {
			debugWriter.println("storeTableColExpressions\tsql statement=" + selectSt );
			debugWriter.flush();
		}
		
		try {
			Statement viewExpressions = connection.createStatement();
			ResultSet viewExprRs = viewExpressions.executeQuery(selectSt);
			while (viewExprRs.next()) {
//				System.out.println("expresison field !!!!!!!!!!!");
				String colName = viewExprRs.getString("column_name");
				String expr = viewExprRs.getString("expression");
				String key = schemaName + "/" + tableName + "/" + colName;
				expressionsFound++;
				columnExpressions.put(key, expr);
			}
			
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (Exception ex) {
			System.out.println("un-known exception caught" + ex.getMessage());
			ex.printStackTrace();
		}
		
		if (doDebug && debugWriter !=null) {
			debugWriter.println("exiting storeTableColExpressions(" +schemaName+ ", " + tableName + ")" );
			debugWriter.flush();
		}
	}
	
	/**
	 * get view information
	 */
	protected void getViews(String catalogName, String schemaName) {
		if (doDebug && debugWriter !=null) {
			debugWriter.println("entering getViews(" +catalogName+ ", " + schemaName + ")" );
			debugWriter.flush();
		}

		try {
//			ResultSet rsViews = dbMetaData.getTables(schemaName, null, null, new String[] { "VIEW" });
			PreparedStatement viewMetadata = null;
			String viewQuery = "SELECT database_name, name, type, user_creator, last_user_modifier, create_date, last_modification_date, description, view_type, folder " +
					"FROM GET_VIEWS() " + 
					"WHERE input_database_name = ? and view_type>0";
			viewMetadata = connection.prepareStatement(viewQuery);
			viewMetadata.setString(1, schemaName);
			if (doDebug && debugWriter !=null) {
				debugWriter.println("getViews:\tprepared sql statement=" +viewQuery );
				debugWriter.flush();
			}


	        ResultSet rsViews = viewMetadata.executeQuery();


			// instead of calling standard jdbc - use this
			// SELECT * FROM GET_VIEWS() WHERE input_database_name = '<catalogName>'
			// will also return the view folder
			
			int viewCount = 0;
			while (rsViews.next()) {
				// Print
				viewCount++;
				// jdbc standard call returns TABLE_NAME
				//String viewName = rsViews.getString("TABLE_NAME");
				
				// denodo GET_VIEWS() returns "name"
				String viewName = rsViews.getString("name");
				
				List<String> values = viewDbNameMap.get(schemaName);
				if (values==null) {
					values = new ArrayList<String>();
				}
				values.add(viewName);
				viewDbNameMap.put(schemaName, values);
				datasetsScanned.add(schemaName + "/" + viewName);


				// System.out.println("found one...");
				System.out.print("\t\t" + rsViews.getString("database_name")  + "." + viewName
						+ " TABLE_TYPE=" + rsViews.getString("view_type")
//						+ " comments=" + rsViews.getString("description")
						);
				//				System.out.println(rsTables.getMetaData().getColumnTypeName(5));
				if (doDebug && debugWriter !=null) {
					debugWriter.println("getViews\t" + " catalog=" + rsViews.getString("database_name")  + " viewname=" + viewName
							+ " TABLE_TYPE=" + rsViews.getString("view_type")
							+ " comments=" + rsViews.getString("description")
							);
					debugWriter.flush();
				}
				
				// get the view sql
				String viewSQL="desc vql view \"" + schemaName + "\".\"" + viewName + "\"";
//				PreparedStatement wrapperStmnt = null;
//				String wrapperQuery = "DESC VQL WRAPPER JDBC ";
				String viewSqlStmnt="";
		        try {
					Statement stViewSql = connection.createStatement(); 
					ResultSet rs = stViewSql.executeQuery(viewSQL);
					while (rs.next()) {
//						System.out.println("\t\twrapper.....");
//						System.out.println("view sql^^^^=" + viewSQL);
						viewSqlStmnt = rs.getString("result");
						
						// @ todo - extract only the view definition - denodo also incudea all dependent objects
//						System.out.println("viewSQL=\n" + result);
						
						// @todo @important - get the wrapper typoe (DF JDBC WF ...
						
						// now we need to extract the DATASOURCENAME='<name>'
						///int dsStart = result.indexOf("DATASOURCENAME=");
//						System.out.println("start pos=" + dsStart + " total length=" + result.length());
						///int dsEnd = result.indexOf("\n", dsStart);
//						System.out.println("start end=" + dsEnd + " total length=" + result.length());
						///if (dsStart>0 && dsEnd < result.length()) {
						///	connectionName = result.substring(dsStart+15, dsEnd);
						///}
					}
				} catch (SQLException e) {
					e.printStackTrace();
				}

				
				
				this.createView(catalogName, schemaName, viewName, rsViews.getString("description"), viewSqlStmnt, rsViews.getString("folder"));
				
				// for denodo - we want to document the expression formula for any calculated fields
				// so for this table - we will store in memory - 
				storeTableColExpressions(schemaName, viewName);


				getColumnsForTable(catalogName, schemaName, viewName, true);
			}
			System.out.println("\tViews extracted: " + viewCount);
			
			// collect for later 0 tge 

		} catch (SQLException e) {
			e.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("getViews - Exception" );
				e.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		} catch (Exception ex) {
			System.out.println("un-known exception caught" + ex.getMessage());
			ex.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("getViews - Exception" );
				ex.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		}
		if (doDebug && debugWriter !=null) {
			debugWriter.println("exiting getViews(" +catalogName+ ", " + schemaName + ")" );
			debugWriter.flush();
		}

	}
	

	/**
	 * get all columns for a table or view, including all datatype and other relevant properties
	 * 
	 * catalogName - not used for denodo (this is sub-classed off generic jdbc)
	 * schemaName - for Denodo, this is the database
	 * tableName - table or view name
	 * isView - true if view, otherwise it is a table
	 * 
	 */
	protected void getColumnsForTable(String catalogName, String schemaName, String tableName, boolean isView) {
		if (doDebug && debugWriter !=null) {
			debugWriter.println("entering getColumnsForTable(" +catalogName+ ", " + schemaName+ ", " + tableName + ", " + isView + ")" );
			debugWriter.flush();
		}
    	int colCount=0;
    	int exprCount=0;
    	try {
    		// Note:  alternate select * from get_view_columns ('policy_asset', 'policy_asset'); - where order will be correct (derive pos)
    		// Note:  if any denodo objects have a $ in the name, the standard JDBC getColumns call will return no rows
    		//        so we need to use CATALOG_VDP_METADATA_VIEWS()
    		//		    ResultSet columns = dbMetaData.getColumns(schemaName, null, tableName, null);
		    
			PreparedStatement viewColumns = null;
			String viewColumnQRY = "SELECT * FROM CATALOG_VDP_METADATA_VIEWS() " +
					"WHERE input_database_name = ? AND input_view_name  = ?";
			viewColumns = connection.prepareStatement(viewColumnQRY);
			viewColumns.setString(1, schemaName);
			viewColumns.setString(2, tableName);
//			System.out.println("executing query" + viewColumnQRY + " passing:" + schemaName + " and " + tableName);
			ResultSet rsViewColumns = viewColumns.executeQuery();
			int aColCount = 0;
			while (rsViewColumns.next()) {
				aColCount++;
				String columnName = rsViewColumns.getString("column_name");
                String typeName = rsViewColumns.getString("column_type_name");
                String columnsize = rsViewColumns.getString("column_type_precision");   // precision matches jdbc lenght - not length
                String pos = Integer.toString(aColCount);
                String comments = rsViewColumns.getString("column_description");
                aColCount = aColCount+0;
//				System.out.println("column found...")
//			}
//			System.out.println("columns really found = " + aColCount);
			
//		    while(columns.next()) {
		    	colCount++;
//                String columnName = columns.getString("COLUMN_NAME");
//                String typeName = columns.getString("TYPE_NAME");
//                String columnsize = columns.getString("COLUMN_SIZE");
//                String pos = columns.getString("ORDINAL_POSITION");
                
                String colKey = schemaName + "/" + tableName + "/" + columnName;
                String exprVal = columnExpressions.get(colKey);
                if (exprVal != null) {
//                	System.out.println("\t\texpression found for field " + colKey + " " + exprVal);
                	exprCount++;
                }
                
                // store the list of columns - for linking later
                elementsScanned.add(colKey);
                              
        		if (doDebug && debugWriter !=null) {
        			debugWriter.println("getColumnsForTable\t\tcolumnn=" + catalogName + "/" + schemaName + "/" + tableName+ "/" + columnName+ "/" + typeName+ "/" + columnsize+ "/" + pos);
        			debugWriter.flush();
        		}

//        		createColumn( );
                if (isView) {
                	this.createViewColumn(catalogName, schemaName, tableName, columnName, typeName, columnsize, pos, exprVal, comments);
                } else {
                	this.createColumn(catalogName, schemaName, tableName, columnName, typeName, columnsize, pos, comments, isView);
                }
                
                // add to name mapping
//                if (!isView) {
                	Map<String, List<String>> schemaMap = colDbNameMap.get(schemaName);
    				if (schemaMap==null) {
    					schemaMap = new HashMap<String, List<String>>();
    					colDbNameMap.put(schemaName, schemaMap);
    				}
    				
    				// check the table is in the schema map
    				List<String> cols = schemaMap.get(tableName);
    				if (cols==null) {
    					// add a new list of columns
    					cols = new ArrayList<String>();
    					schemaMap.put(tableName, cols);
    					
    				}
    				cols.add(columnName);
//                }

                		
		    }  // end for each column
    	} catch (Exception ex) {
    		System.out.println("error extracting column metadata...");
    		ex.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("getColumnsForTable - Exception" );
				ex.printStackTrace(debugWriter);
				debugWriter.flush();
			}
    	}
	    System.out.println(" columns: " + colCount + " expressions:" + exprCount);
	    if (colCount==0) {
	    	System.out.println("why 0 cols??");
	    }

	    if (doDebug && debugWriter !=null) {
			debugWriter.println("exiting getColumnsForTable(" +catalogName+ ", " + schemaName+ ", " + tableName + ", " + isView + ")" );
			debugWriter.flush();
		}

	}
	
	protected Map<String, String> privateDepsMap = new HashMap<String, String>();
	
	/**
	 * query the view_dependencies(<db>, <view>) stored procedure to get view level lineage
	 * 
	 * Note:  we need to be careful with how denodo is queried - the procedure can return some strange results
	 *        when joins are added  (private views are used)
	 *        best method at the moment is:-
	 *        
	 *        select * from   view_dependencies ('<db>', '<view>' )
	 *        where depth=1 and view_type != 'Base View'
	 *        order by input_view_database_name, input_view_name, view_identifier desc
	 *        
	 *        then we process each record - 
	 *          - if the input_view_name = view_name & private_view = false
	 *               make a direct connection - if depencency_name (a view name) is a real object (not a private view)
	 *          - if the inut_view_name != view_name & private_view = true
	 *               it is an indirect reference 
	 *               if the depencency_name object is valid - link it (otherwise skip it - since it is just a private view)
	 * 
	 * @param dbName the database that the view belongs to
	 * @param viewName the view that we need lineage for
	 */
	protected void extractViewLevelLineage(String dbName, String viewName) {
		if (doDebug && debugWriter !=null) {
			debugWriter.println("entering extractViewLevelLineage(" +dbName+ ", " + viewName + ")" );
			debugWriter.flush();
		}

		System.out.println("\t\tview lineage extraction - using view_dependencies stored procedure for: " + dbName + "/" + viewName);
		String query="select * " + 
				"from   view_dependencies (?, ?)  " + 
				"where depth=1 and view_type != 'Base View' " //+ 
				//"order by input_view_database_name, input_view_name, view_identifier desc"
				;
		if (doDebug && debugWriter !=null) {
			debugWriter.println("extractViewLevelLineage\tpreparedStatement=" + query);
			debugWriter.flush();
		}
		
//		System.out.println("structs scanned..." + datasetsScanned.size());
//		System.out.println(datasetsScanned);
		
//        int recCount=0;		
        try {
    		PreparedStatement deps = connection.prepareStatement(query);
    		deps.setString(1, dbName);
			deps.setString(2, viewName);
	        ResultSet rsDeps = deps.executeQuery();
		    while(rsDeps.next()) {
//		    	recCount++;
//		    	boolean isDebug=false;
		    	String inViewName = rsDeps.getString("input_view_name");
		    	String returnedviewName = rsDeps.getString("view_name");
		    	String privateView = rsDeps.getString("private_view");
//		    	String fromDB = rsDeps.getString("view_database_name");
		    	String fromDB = rsDeps.getString("dependency_database_name");
		    	String fromTab = rsDeps.getString("dependency_name");
//		    	String viewType = rsDeps.getString("view_type");
//    			if (viewName.equalsIgnoreCase("pspstatus_Project_Summary") ) {
//    				System.out.println("start debug for view lineage here... rec=" + recCount);
//    				System.out.println("\ttest 1: " + inViewName.equals(returnedviewName) + " && " + privateView.equals("false") + " = " + ( inViewName.equals(returnedviewName) && privateView.equals("false") ));
//    				System.out.println("\ttest 2: " + (! inViewName.equals(returnedviewName)) + " && " + privateView.equals("true") + " " + returnedviewName.startsWith("_" + inViewName.toLowerCase() + "_") + " = " + ( ! inViewName.equals(returnedviewName) && privateView.equals("true")  && returnedviewName.startsWith("_" + inViewName.toLowerCase() + "_") ) );
//    				isDebug=true;
//    			}

		    	
//		    	System.out.println("\t3 way: " + inViewName + " " + returnedviewName + " " + privateView + " " + fromTab);
		    	if (
		    			( inViewName.equals(returnedviewName) && privateView.equals("false") )
		    			|
		    			( privateView.equals("true") && returnedviewName.startsWith("_" + inViewName.toLowerCase() + "_") )
//		    			( ! inViewName.equals(returnedviewName) && privateView.equals("true") && returnedviewName.startsWith("_" + inViewName.toLowerCase() + "_") )
		    			
		    	   ) {
//		    		if (isDebug) {
//		    			System.out.println("ready to debug in view lineage");
//		    		}
		    		// case 1
		    		String objKey = fromDB + "/" + fromTab;
		    		System.out.println("extractViewLevelLineage should link: " + objKey);
		    		if (datasetsScanned.contains(objKey)) {
//		    			System.out.println("\t\t\t OK - direct link found...." + objKey);
				    	String lefttabId = databaseName + "/" + fromDB + "/" +fromTab;
				    	String righttabId = databaseName + "/" + dbName + "/" +viewName;
			    		linksWriter.writeNext(new String[] {"core.DataSetDataFlow",lefttabId,righttabId});
//			    		if (isDebug) {
//			    			System.out.println("\t\t\twriting table level lineage: " +  lefttabId + " ==>> " + righttabId);
//			    			System.out.println("$$$");
//			    		}
			    		totalTableLineage++;
		    		} else {
		    			// 
		    			//System.out.println("\t\t\t probably a link to a virtual table" + objKey);	
		    			// add to (verbose) logging (to be implemented)
		    		}
		    	} 
		    }
		} catch (SQLException e) {
			System.out.println("sql Exception: " + e.getMessage());
			e.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("extractViewLevelLineage - Exception" );
				e.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		} catch (Exception ex) {
			System.out.println("unknown exception found " + ex.getMessage());
			ex.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("extractViewLevelLineage - Exception" );
				ex.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		}

        if (doDebug && debugWriter !=null) {
			debugWriter.println("exiting extractViewLevelLineage(" +dbName+ ", " + viewName + ")" );
			debugWriter.flush();
		}
 
	}
	
	protected void extractViewLevelLineageRefactored(String dbName, String viewName) {
		if (doDebug && debugWriter !=null) {
			debugWriter.println("entering extractViewLevelLineage(" +dbName+ ", " + viewName + ")" );
			debugWriter.flush();
		}

		System.out.println("\t\tview lineage extraction - using view_dependencies stored procedure for: " + dbName + "/" + viewName);
		String query="select * " + 
				"from   view_dependencies (?, ?)  " + 
				"where depth=1 and view_type != 'Base View' " //+ 
				//"order by input_view_database_name, input_view_name, view_identifier desc"
				;
		if (doDebug && debugWriter !=null) {
			debugWriter.println("extractViewLevelLineage\tpreparedStatement=" + query);
			debugWriter.flush();
		}
		
//		System.out.println("structs scanned..." + datasetsScanned.size());
//		System.out.println(datasetsScanned);
    	Map<String, List<String>> vcache = new HashMap<String, List<String>>();

		
//        int recCount=0;		
        try {
    		PreparedStatement deps = connection.prepareStatement(query);
    		deps.setString(1, dbName);
			deps.setString(2, viewName);
	        ResultSet rsDeps = deps.executeQuery();
		    while(rsDeps.next()) {
//		    	recCount++;
//		    	boolean isDebug=false;
//		    	String inViewName = rsDeps.getString("input_view_name");
		    	String viewDB = rsDeps.getString("view_database_name");
		    	String returnedviewName = rsDeps.getString("view_name");
		    	String privateView = rsDeps.getString("private_view");
//		    	String fromDB = rsDeps.getString("view_database_name");
		    	String fromDB = rsDeps.getString("dependency_database_name");
		    	String fromTab = rsDeps.getString("dependency_name");
//		    	String viewType = rsDeps.getString("view_type");
		    	
	    		String cachKey = viewDB + "//" + returnedviewName;
	    		String cacheVal = fromDB + "//" + fromTab;
	    		
//	    		System.out.println("record: " + recCount);

		    	// cache processing....
		    	if (privateView.equals("true")) {
		    		// cache the vale for lookup when non private views are processed
		    		List<String> cachedVals;
		    		if (vcache.containsKey(cachKey)) {
		    			cachedVals = vcache.get(cachKey);
		    		} else {
		    			// new key
		    			cachedVals = new ArrayList<String>();
		    			vcache.put(cachKey, cachedVals);
		    		}
			    		
		    		// add the val
		    		if (! cachedVals.contains(cacheVal) ) {
//			    		System.out.println("adding val to key=" + cachKey + " val=" + cacheVal);
		    			cachedVals.add(cacheVal);
		    		}
			    		
			    	// no need to process further (we stored the value in the cache
			    	continue;
		    	}

		    	if (privateView.equals("false") && viewDB.equals(dbName) && viewName.equals(returnedviewName)) {
		    		List<String> objectstoLink = new ArrayList<String>();
//		    		System.out.println("lookup...." + recCount + " " + cachKey + " val=" + cacheVal);
		    		if (vcache.containsKey(cacheVal)) {
		    			// winner 
//		    			System.out.println("\tlookup: " + cacheVal);
//		    			System.out.println("\t\tret: " + vcache.get(cacheVal));
		    			objectstoLink = getColumnsFromCache(vcache, fromDB + "//" + fromTab, new ArrayList<String>());
//		    			System.out.println("\t\tresult?: " + objectstoLink.size() + " " + objectstoLink);

		    		} else {
//		    			System.out.println("\tdirect ref: " + cacheVal);
		    			objectstoLink.add(cacheVal);
		    		}
		    		
//	    			System.out.println("\tto be linked?: " + objectstoLink.size() + " " + objectstoLink);
	    			
	    			// link the referenced objects
		    		for (String refdCol: objectstoLink) {
		    			String[] values = refdCol.split("//");
		    			if (values.length==2) {
		    				String leftDB = values[0];
		    				String leftVW = values[1];
//		    				System.out.println("\tready to link: " + leftDB + "." + leftVW);
		    				
				    		String objKey = leftDB + "/" + leftVW;
//				    		System.out.println("\t\t\textractViewLevelLineage should link: " + objKey);
				    		if (datasetsScanned.contains(objKey)) {
//				    			System.out.println("\t\t\t OK - direct link found...." + objKey);
						    	String lefttabId = databaseName + "/" + leftDB + "/" +leftVW;
						    	String righttabId = databaseName + "/" + dbName + "/" +viewName;
					    		linksWriter.writeNext(new String[] {"core.DataSetDataFlow",lefttabId,righttabId});
//					    		if (isDebug) {
//					    			System.out.println("\t\t\twriting table refactored level lineage: " +  lefttabId + " ==>> " + righttabId);
//					    			System.out.println("$$$");
//					    		}
					    		totalTableLineage++;
				    		} else {
				    			System.out.println("\t\textractViewLevelLineageRefactored lookup not found: " + objKey);
				    			// log it???
				    		}
		    			}
		    		}


		    	}
		    	
		    	
		    	

		    }
		} catch (SQLException e) {
			System.out.println("sql Exception: " + e.getMessage());
			e.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("extractViewLevelLineage - Exception" );
				e.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		} catch (Exception ex) {
			System.out.println("unknown exception found " + ex.getMessage());
			ex.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("extractViewLevelLineage - Exception" );
				ex.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		}

        if (doDebug && debugWriter !=null) {
			debugWriter.println("exiting extractViewLevelLineage(" +dbName+ ", " + viewName + ")" );
			debugWriter.flush();
		}
 
	}

	
	/**
	 * extact view column level lineage - internal to denodo
	 * similar to view level lineage - but it needs to be called individually for each column
	 * (if called with null as the column, we get results for all columns in a view - but the resultset is not accurate)
	 * 
	 * so there will be a lot of calls to the db - but it is the ONLY way to get the right result
	 * 
	 * @param dbName
	 * @param viewName
	 * @param columnName
	 */
	protected void extractViewColumnLevelLineage(String dbName, String viewName, String columnName) {
		if (doDebug && debugWriter !=null) {
			debugWriter.println("entering extractViewColumnLevelLineage(" +dbName+ ", " + viewName + ", " + columnName + ")" );
			debugWriter.flush();
		}
//		System.out.println("view column lineage extraction - using column_dependencies stored procedure");
		String query="select * " + 
				"from   COLUMN_DEPENDENCIES (?, ?, ?)  " + 
				"where depth=1 "; //+ 
				// "order by input_view_name, view_name, column_name, view_identifier desc, depth;"
				//;
		if (doDebug && debugWriter !=null) {
			debugWriter.println("extractViewColumnLevelLineage\tpreparedStatement=" + query );
			debugWriter.flush();
		}
				
//		System.out.println("elements scanned..." + elementsScanned.size());
//		System.out.println(elementsScanned);
		
//        int recCount=0;		
        try {
    		PreparedStatement deps = connection.prepareStatement(query);
    		deps.setString(1, dbName);
			deps.setString(2, viewName);
			deps.setString(3, columnName);
	        ResultSet rsDeps = deps.executeQuery();
		    while(rsDeps.next()) {
//		    	recCount++;
		    	String inViewName = rsDeps.getString("input_view_name");
//		    	String toColName = rsDeps.getString("column_name");
		    	String returnedviewName = rsDeps.getString("view_name");
		    	String privateView = rsDeps.getString("private_view");
//		    	String fromDB = rsDeps.getString("view_database_name");
		    	String fromDB = rsDeps.getString("dependency_database_name");
		    	String fromTab = rsDeps.getString("dependency_name");
		    	String fromCol = rsDeps.getString("dependency_column_name");
//		    	String depType = rsDeps.getString("dependency_type");
		    	
		    	if (  	( inViewName.equals(returnedviewName) && privateView.equals("false") )
		    			|
		    			( privateView.equals("true")  && returnedviewName.startsWith("_" + inViewName.toLowerCase() + "_") )   
//		    			( ! inViewName.equals(returnedviewName) && privateView.equals("true")  && returnedviewName.startsWith("_" + inViewName.toLowerCase() + "_") )   
		    		) {
		    		String objKey = fromDB + "/" + fromTab + "/" + fromCol;
		    		// if it is an actual object reference - link it
		    		if (elementsScanned.contains(objKey)) {
//		    			System.out.println("\t\t\tok - direct link found...." + objKey);
				    	String leftId = databaseName + "/" + fromDB + "/" +fromTab + "/" + fromCol;
				    	String rightId = databaseName + "/" + dbName + "/" +viewName + "/" + columnName;
			    		linksWriter.writeNext(new String[] {"core.DirectionalDataFlow",leftId,rightId});		    			
			    		totalColumnLineage++;
		    		} else {
		    			// it could be a virtual column (for a join etc, or an expression field)
		    			// special case - for expressions the parent object might match - but the column name may be a multi-field (with a comma)
		    			if (fromCol != null && fromCol.contains(",")) {
		    				expressionsProcessed++;
//		    				System.out.println("expr field...");
		    				if (datasetsScanned.contains(fromDB + "/" + fromTab)) {
//		    					System.out.println("split them here");
					    		String[] exprParts = fromCol.split(",");
					    		
					    		//@todo refactor here - too much copy/paste of code
					    		for (String exprPart: exprParts) {
					    			expressionLinks++;
//					    			System.out.println("\t\t\tlinking ...." + exprPart);
					    			String  leftId = databaseName + "/" + fromDB + "/" +fromTab + "/" + exprPart;
					    			String rightId = databaseName + "/" + dbName + "/" +viewName + "/" + columnName;
						    		linksWriter.writeNext(new String[] {"core.DirectionalDataFlow",leftId,rightId});		    			
//						    		System.out.println("\t\t\t\twriting column level lineage: " +  leftId + " ==>>> " + rightId);
						    		totalColumnLineage++;
					    		}

		    				}
		    				// end - is an expression reference
		    			}  else {
//		    				System.out.println("\t\t\tprobably a link to a virtual table column " + objKey);	
		    			}
		    		}
		    	} 
		    }
		} catch (SQLException e) {
			e.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("extractViewColumnLevelLineage - Exception" );
				e.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		} catch (Exception ex) {
			System.out.println("unknown exception found " + ex.getMessage());
			ex.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("extractViewColumnLevelLineage - Exception" );
				ex.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		}
 
		if (doDebug && debugWriter !=null) {
			debugWriter.println("exiting extractViewColumnLevelLineage(" +dbName+ ", " + viewName + ", " + columnName + ")" );
			debugWriter.flush();
		}
	}	
	
	/** - not used
	private String getMappedViewNameFromMap(String from, Map<String,String> theMap) {
		//		String actual = "";
		while (theMap.containsKey(from)) {
			//			System.out.println("lookup: " + from);
			from = theMap.get(from);
		}
		return from;
	}
	*/
	
	static int ldepth=0;
	
	/**
	 * helper function to lookup cached values from column depencendies
	 * @param theCache - catche to lookup the value(s)
	 * @param theKey - key to find all matching actual values (not internal columns)
	 * @param theListToReturn  - the list to return (this is recursive - first call will be an empty list)
	 * @return
	 */
	protected List<String> getColumnsFromCache( Map<String, List<String>> theCache, String theKey, ArrayList<String> theListToReturn) {
		ldepth++;
				 
//		 System.out.println("getColumnsFromCache key=" + theKey + " key found=" + theCache.containsKey(theKey) + " depth=" + ldepth);
		 
		 if (theCache.containsKey(theKey)) {
			 List<String> tmpList = theCache.get(theKey);
			 for (String tmpString: tmpList) {
				 // recursive call to getcolumnsFromCache
				 if (theCache.containsKey(tmpString)) {
//					 System.out.println("testing: " + tmpString);
					 getColumnsFromCache(theCache, tmpString, theListToReturn);
				 } else {
					 theListToReturn.add(tmpString);
				 }
			 }
		 }		 
		 
	 
//		 System.out.println("\t\treturning after " + iterations + " " + ldepth + " cachedList=" + theListToReturn + " theKey=" + theKey);
		 ldepth--;
		 return theListToReturn;
		
	}
	
	/**
	 * extract view column level lineage - internal to denodo, for a view (not column by column which is very slow on larger implemneations)
	 * similar to view level lineage -
	 * 
	 * @param dbName
	 * @param viewName
	 */
	protected void extractViewColumnLevelLineageRefactored(String dbName, String viewName) {
		if (doDebug && debugWriter !=null) {
			debugWriter.println("entering extractViewColumnLevelLineageRefactored(" +dbName+ ", " + viewName + ", NULL)" );
			debugWriter.flush();
		}
				
		String query="select * " + 
				"from   COLUMN_DEPENDENCIES (?, ?, ?)  " + 
				"where depth=1 "; //+ 
				// "order by input_view_name, view_name, column_name, view_identifier desc, depth;"
				//;
		if (doDebug && debugWriter !=null) {
			debugWriter.println("extractViewColumnLevelLineage\tpreparedStatement=" + query );
			debugWriter.flush();
		}
		
		// nodupes
		Set<String> colUniqeLineage = new HashSet<String>();		
				
//		System.out.println("elements scanned..." + elementsScanned.size());
//		System.out.println(elementsScanned);
    	String sourceDB="";
    	String sourceVw="";
    	String sourceCl="";
    	
//    	List<String> processedCols = new ArrayList<String>();
    	Map<String, List<String>> cache = new HashMap<String, List<String>>();
    	
    	String lastCachedActualColumn="";
    	boolean lastLinkedActual = false;
    	
		
//        int recCount=0;		
        try {
    		PreparedStatement deps = connection.prepareStatement(query);
    		deps.setString(1, dbName);
			deps.setString(2, viewName);
			deps.setString(3, null);
	        ResultSet rsDeps = deps.executeQuery();
		    while(rsDeps.next()) {
//		    	recCount++;
//		    	if (viewName.equals("etq_capa_CAPATiming") && recCount>=656) {

//		    	String inViewName = rsDeps.getString("input_view_name");
		    	String toColName = rsDeps.getString("column_name");
		    	String returnedviewName = rsDeps.getString("view_name");
		    	String privateView = rsDeps.getString("private_view");
		    	String viewDB = rsDeps.getString("view_database_name");
		    	String fromDB = rsDeps.getString("dependency_database_name");
		    	String fromTab = rsDeps.getString("dependency_name");
		    	String fromCol = rsDeps.getString("dependency_column_name");
		    	String depType = rsDeps.getString("dependency_type");
//		    	String viewType = rsDeps.getString("view_type");
		    	String expr = rsDeps.getString("expression");
		    	
//		    	System.out.println("row: " + recCount);
	    		String cachKey = viewDB + "//" + returnedviewName + "//" + toColName;
	    		String cacheVal = fromDB + "//" + fromTab + "//" + fromCol;
		    	
		    	if (lastLinkedActual==true) {
		    		// see 
//		    		System.out.println("checking for actual = " + lastCachedActualColumn + " = " + cachKey);
		    		if (lastCachedActualColumn.contentEquals(cachKey)) {
		    			// same
//		    			System.out.println("same continue...");
		    		} else {
		    			// reset cache??
//		    			System.out.println("different - un-cache");
		    			cache.clear();
		    			lastLinkedActual=false;
		    			
		    		}
		    	}
		    	
	    		
		    	// cache processing....
		    	if (privateView.equals("true")) {
			    	if (fromCol != null) {
			    		List<String> cachedVals;
			    		if (cache.containsKey(cachKey)) {
			    			cachedVals = cache.get(cachKey);
			    		} else {
			    			// new key
//			    			System.out.println("adding key=" + cachKey);
			    			cachedVals = new ArrayList<String>();
			    			cache.put(cachKey, cachedVals);
			    		}
			    		
			    		// add the val
			    		if (! cachedVals.contains(cacheVal) ) {
//			    			System.out.println("adding val to key=" + cachKey + " val=" + cacheVal);
			    			cachedVals.add(cacheVal);
			    		}
			    		
			    	}

			    	// no need to process further (we stored the value in the cache
			    	continue;
		    		
		    	}
		    	
		    	// special case 
		    	// see if the stored settings should be reset (when a new datasource is referenced)
		    	if (fromCol==null && ! depType.equals("Predefined Storedprocedure")) {
//		    		System.out.println("!! Predefined Storedprocedure !!" + fromTab + " row=" +recCount);
		    		continue;
		    	}

//		    	System.out.println("test " + (! dbName.equals(viewDB)) + "  && "  + (! returnedviewName.equals(viewName)) );
		    	if ( ! dbName.equals(viewDB) | ! returnedviewName.equals(viewName) ) {
		    		// skip writing
//		    		System.out.println("row=" + recCount + " db/tab not match - skipping" );
		    		continue;
//		    	} else {
//		    		// database and record matches - do we reset???
		    	}
		    	// now we must have an actual view and not a procedure reference to link
		    	
		    	// if it is a preDefined Stored procedure - need to create some new objects
		    	if (depType.equals("Predefined Storedprocedure")) {
		    		System.out.println("\t\t\tprocedure object reference found (should be created/linked): " + " admin//" + fromTab + "//" + fromCol + " expression=" + expr);
		    		// also change the db to admin in cacheval
		    	}
		    	
//		    	viewDB + "//" + returnedviewName
//		    	if (lastCachedActualColumn)
		    	lastLinkedActual = true;
		    	lastCachedActualColumn = cachKey;
		    	
		    	//
//		    	System.out.println("ready to write links for " + cachKey + " val=" + cacheVal);
//	    		System.out.println(">>>>>\n\tCache lookup for : " + fromDB + "//" + fromTab + "//" + fromCol);
	    		List<String> theColsToLink = new ArrayList<String>();
	    		if (! fromTab.startsWith("_")) {
	    			// forget the cache
//	    			System.out.println("\t\t\tforget the cache - it is direct...");
	    			theColsToLink.add(fromDB + "//" + fromTab + "//" + fromCol);
	    		} else {
	    			theColsToLink = getColumnsFromCache(cache, fromDB + "//" + fromTab + "//" + fromCol, new ArrayList<String>());
	    		}
//	    		System.out.println("\t" + theColsToLink);
//	    		System.out.println("<<<<\n");

//		    	System.out.println("ready to write links for " + cachKey + " theCols=" + theColsToLink);

	    		for (String refdCol: theColsToLink) {
	    			String[] values = refdCol.split("//");
	    			if (values.length==3) {
	    				sourceDB = values[0];
	    				sourceVw = values[1];
	    				sourceCl = values[2];
	    				
			    		String tgtKey = dbName + "/" + returnedviewName + "/" + toColName;
			    		String srcKey = sourceDB + "/" +sourceVw + "/" + sourceCl;
//			    		System.out.println("\twrite it" + toColName + " " + sourceDB + "/" + sourceVw + "/" + sourceCl);
//			    		System.out.println("\twrite it" + srcKey + " -> " + tgtKey);
			    		if (elementsScanned.contains(srcKey) && elementsScanned.contains(tgtKey)) {
//			    			System.out.println("\t\t\tok - direct link found...." + objKey);
					    	String leftId = databaseName + "/" + srcKey;
					    	String rightId = databaseName + "/" + tgtKey;
					    	if (! colUniqeLineage.contains(leftId+":"+rightId)) {
					    		linksWriter.writeNext(new String[] {"core.DirectionalDataFlow",leftId,rightId});		    			
//					    		System.out.println("\t\t\t\twriting column level lineage: " +  leftId + " ==>> " + rightId);
					    		colUniqeLineage.add(leftId+":"+rightId);
					    		totalColumnLineage++;				    								    		
					    	} else {
//					    		System.out.println("\t\tdup: " + leftId+":"+rightId);
					    	}
			    		} else {
			    			if (sourceCl != null && sourceCl.contains(",")) {
			    				expressionsProcessed++;
//			    				System.out.println("expr field...");
			    				if (datasetsScanned.contains(sourceDB + "/" + sourceVw)) {
//			    					System.out.println("split them here");
						    		String[] exprParts = sourceCl.split(",");
						    		
						    		//@todo refactor here - too much copy/paste of code
						    		for (String exprPart: exprParts) {
						    			expressionLinks++;
//						    			System.out.println("\t\t\tlinking ...." + exprPart);
						    			String  leftId = databaseName + "/" +  sourceDB + "/" +sourceVw + "/" + exprPart;
								    	String rightId = databaseName + "/" + tgtKey;
							    		linksWriter.writeNext(new String[] {"core.DirectionalDataFlow",leftId,rightId});		    			
//							    		System.out.println("\t\t\t\twriting column level lineage from expr: " +  leftId + " ==>>> " + rightId);
							    		totalColumnLineage++;
						    		}

			    				}
			    				// end - is an expression reference
			    			}  
			    			
			    		}
	    			}
	    		}
		    	// end of new re-factoring
		    	

		    }  // next record from db
		} catch (SQLException e) {
			e.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("extractViewColumnLevelLineage - Exception" );
				e.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		} catch (Exception ex) {
			System.out.println("unknown exception found " + ex.getMessage());
			ex.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("extractViewColumnLevelLineage - Exception" );
				ex.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		}
 
		if (doDebug && debugWriter !=null) {
			debugWriter.println("exiting extractViewColumnLevelLineage(" +dbName+ ", " + viewName + ", NULL)" );
			debugWriter.flush();
		}
	}	
	
	
	
	/**
	 * extra processing can call some dbms specific functions
	 * e.g. internal linage for denodo
	 * external lineage (back to s3 files) for athena
	 */
	protected void extraProcessing() {
		/*
		 * note - AxaXL had some problems in this method & the extractViewColumn level lineage - so adding more try/catch options
		 */
		if (doDebug && debugWriter !=null) {
			debugWriter.println("entering extraProcessing()" );
			debugWriter.println("memory dump for column names");
			debugWriter.println("colDbNameMap.keySet()=" +colDbNameMap.keySet());
			debugWriter.println("colDbNameMap.values()=" +colDbNameMap.values());
			debugWriter.flush();
			
		}
		System.out.println("");
		System.out.println("Denodo specific processing - extracting view lineage...");

		try {
			// for each schema - then view...
			for (String schema : viewDbNameMap.keySet()) {
				System.out.println("\tschema=" + schema);
//		        long schStart = System.currentTimeMillis();

				for (String view : viewDbNameMap.get(schema)) {
//			        int numCols = 0;
//			        long vstart = System.currentTimeMillis();

					System.out.println("\t\tview=" + view);
					if (epSkipViews.contains(schema + "." + view)) {
						System.out.println("extract view|column lineage skipped for: " + schema + "." + view);
						if (doDebug && debugWriter !=null) {
							debugWriter.println("\textract view|column lineage skipped for: \" + schema + \".\" + view");
							debugWriter.flush();
						}

					} else {
				        long start = System.currentTimeMillis();
				        // call the view level lineage extraction
//						this.extractViewLevelLineage(schema, view);	
						this.extractViewLevelLineageRefactored(schema, view);	
						
						this.extractViewColumnLevelLineageRefactored(schema, view);
				        
						long end1 = System.currentTimeMillis();		
						long totalMillis = end1-start;
//						String timeTaken = 	String.format("%d min, %d sec", 
//							    TimeUnit.MILLISECONDS.toMinutes(totalMillis),
//							    TimeUnit.MILLISECONDS.toSeconds(totalMillis) - 
//							    TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(totalMillis))
//							);
//						System.out.println("extractViewLevelLineage() time: " + timeTaken);
						System.out.println("\t\textractViewLevelLineage() time: " + totalMillis + "ms");
						/**
				    	Map<String, List<String>> tableMap = colDbNameMap.get(schema);
				    	// # BUG Axa XL - check for nulls here - we have a case where extract view lineage fails (still troubleshooting)
				    	if (tableMap == null) {
				    		System.out.println("column level lineage not possible for view : " + schema + "." + view + " tableMap==null");
							if (doDebug && debugWriter !=null) {
								debugWriter.println("\tcolumn level lineage not possible for view : " + schema + "." + view +  " tableMap==null");
								debugWriter.flush();
							}
				    		
				    	} else {
							if (doDebug && debugWriter !=null) {
								debugWriter.println("\ttableMap values used for view=" + view + " is: " + tableMap.get(view));
								debugWriter.flush();
							}
				    		
							try {
						    	for (String col : tableMap.get(view)) {
						    		numCols++;
							        start = System.currentTimeMillis();		
									this.extractViewColumnLevelLineage(schema, view, col);
									long end2 = System.currentTimeMillis();
									totalMillis = end2-start;
									System.out.println("extractViewColumnLevelLineage() time: " + totalMillis + " ms");

						    	}
							} catch (Exception ex) {
								System.out.println("exception found for columns for " + view + " in extraProcessing.  view/column lineage will not be possible");
								ex.printStackTrace();
								if (doDebug && debugWriter !=null) {
									debugWriter.println("\texception found for columns for " + view + " in extraProcessing.  view/column lineage will not be possible");
									ex.printStackTrace(debugWriter);
									debugWriter.flush();
									
								}

							}
				    	}  // end of column level processing (each individual column for a view
				    	*/
					}

				} // each view

			}
		} catch (Exception ex) {
			System.out.println("exception raised in first part of extra processing - " + ex.getMessage());
			ex.printStackTrace();
			if (doDebug && debugWriter !=null) {
				ex.printStackTrace(debugWriter);
				debugWriter.flush();
			}
		}
		
		try {
	        long start = System.currentTimeMillis();
			System.out.println("\textra processing for lineage outside of denodo (custom lineage)...");
			String tableSourcedFromSQL="SELECT * FROM GET_SOURCE_TABLE() " + 
					"WHERE input_database_name = ? " + 
					"AND input_view_name = ?";
			if (doDebug && debugWriter !=null) {
				debugWriter.println("extraProcessing\tsqlstatement=" + tableSourcedFromSQL);
				debugWriter.flush();
			}
			PreparedStatement tableDeps = null;
			// for each schema - then view...
			int allCustLineageCount=0;
			
			List<String> tableLevelExternalLinks = new ArrayList<String>();
			
			for (String schema : tableDbNameMap.keySet()) {
//				System.out.println("\tschema=" + schema);
				for (String table : tableDbNameMap.get(schema)) {
//					System.out.println("\t\ttable=" + table);
					if (epSkipViews.contains(schema + "." + table)) {
						System.out.println("extract view|column custom lineage skipped for: " + schema + "." + table);
						if (doDebug && debugWriter !=null) {
							debugWriter.println("\textract view|column custom lineage skipped for: " + schema + "." + table);
							debugWriter.flush();
						}
					} else {

						// only process if the wrapper type is JDBC (skip for others)
						String wrapperType = this.tableWrapperTypes.get(schema + "." + table);
						System.out.print("\t" + schema + "." + table + "  wrapperType=" + wrapperType);

						
						int custLineageCount=0;
						if (wrapperType != null && wrapperType.equalsIgnoreCase("JDBC")) {
					
							try {
								tableDeps = connection.prepareStatement(tableSourcedFromSQL);
								tableDeps.setString(1, schema);
								tableDeps.setString(2, table);
			
						        ResultSet rsDeps = tableDeps.executeQuery();
						        
			//			        System.out.println("\t\tquery executed to get dependencies using COLUMN_DEPENDENCIES() call");
			//			        int tabLvlLineageCount=0;
							    while(rsDeps.next()) {
							    	String fromSchema = rsDeps.getString("source_schema_name");
							    	String fromCatalog = rsDeps.getString("source_catalog_name");
							    	if (fromSchema==null) {
							    		fromSchema=fromCatalog;
							    	}
							    	String fromTab = rsDeps.getString("source_table_name");
			//				    	String fromSQL = rsDeps.getString("sqlsentence");
							    	String viewWrapperName = rsDeps.getString("view_wrapper_name");
							    	System.out.println(" wrapper=" + viewWrapperName);
							    	String connectionName = getConnectionNameFromWrapper(schema, viewWrapperName) + ":" + wrapperType;
//							    	String connectionName = getConnectionNameFromWrapper(schema, viewWrapperName);
							    			
			//				    	System.out.println("\t\t\tsourced from: connectio= " + connectionName + " " + fromSchema + "." + fromTab + " sql=" + fromSQL);
							    	// get the columns for the table...
							    	// refactor - call a function to do this
							    	Map<String, List<String>> tableMap = colDbNameMap.get(schema);
//							    	System.out.println("test??? " + tableMap.get(table));
							    	if (tableMap == null || tableMap.get(table)==null) {
							    		System.out.println("error - no tableMap for schema " + schema + " table=" + table);
							    	} else {
								    	for (String col : tableMap.get(table)) {
				//				    		System.out.println("\t\t\t\tColumn lineage: " + col);
								    		
								    		String leftId = fromSchema + "/" + fromTab + "/" + col;
								    		String rightId = schema + "/" + table + "/" + col;
								    		
								    		String tableLinkLey = connectionName + "/" + fromSchema + "/" + fromTab + ":" + schema + "/" + table;
//								    		String[] custLineage = new String[] {"",connectionName.replaceAll("\"", ""), databaseName,leftId,rightId};
											if (exportCustLineageInScanner) {
//									    		custLineageWriter.writeNext(new String[] {"core.DirectionalDataFlow","X"+ connectionName.replaceAll("\"", ""), databaseName,leftId,rightId,""});
									    		
									    		if (!tableLevelExternalLinks.contains(tableLinkLey)) {
													
													tableLevelExternalLinks.add(tableLinkLey);
													// internal way ($edlRes://")
										    		custLineageWriter.writeNext(new String[] {"core.DataSetDataFlow",connectionName.replaceAll("\"", ""), 
										    				"",
										    				fromSchema + "/" + fromTab,
										    				"$etlRes://" +  databaseName + "/" +schema + "/" + table,
										    				""});

										    		// both left| right connection assignment - not needed 
//										    		custLineageWriter.writeNext(new String[] {"core.DataSetDataFlow", wrapperType + "__" + connectionName.replaceAll("\"", ""), 
//										    				"denodo_" +databaseName,
//										    				fromSchema + "/" + fromTab,
//										    				schema + "/" + table,
//										    				""});

												}
//									    		custLineageWriter.writeNext(new String[] {"core.DirectionalDataFlow",wrapperType + "__" + connectionName.replaceAll("\"", ""), "denodo_" +databaseName,leftId,rightId,""});
												// custom lineage format whem embedded into the scanner - needs ETLContext column too (or it will fail)
												// internal way ($edlRes://")
												rightId = "$etlRes://" +  databaseName + "/" + schema + "/" + table + "/" + col;
												custLineageWriter.writeNext(new String[] {"core.DirectionalDataFlow",connectionName.replaceAll("\"", ""), "",leftId,rightId,""});
  												

											} else {
									    		custLineageWriter.writeNext(new String[] {"",connectionName.replaceAll("\"", ""), databaseName,leftId,rightId});
												
											}
								    		custLineageCount++;
								    		allCustLineageCount++;
//								    		custLineageWriter.writeNext(custLineage);
				//				    		System.out.println(Arrays.toString(custLineage));
								    	}
							    	}
			
							    }
						        
						        rsDeps.close();
						        System.out.println("\t\t\t" + "custom lineage links written=" + custLineageCount);
						    } catch (SQLException e ) {
						    	System.out.println("\n**************  error executing query." + e.getMessage() + "\n**************  end of error");
			//			        e.printStackTrace();
								if (doDebug && debugWriter !=null) {
									debugWriter.println("extraProcessing - Exception" );
									e.printStackTrace(debugWriter);
									debugWriter.flush();
								}
						    } catch (Exception ex) {
						    	System.out.println("unknown error" + ex.getMessage());
						    	ex.printStackTrace();
								if (doDebug && debugWriter !=null) {
									debugWriter.println("extraProcessing - Exception, unknown error" );
									ex.printStackTrace(debugWriter);
									debugWriter.flush();
								}	    	
						    }
						   // end of JDBC wrapper type
						}  else {
							// non jdbc - log it to the console
							System.out.println("\twrapper type:" + wrapperType + " not yet supported");						
						}
					}
		
//					System.out.println("total custom lineage links created: " + allCustLineageCount);
					
					
				} // each table
			}  // each schema
			System.out.println("total custom lineage links created: " + allCustLineageCount);
			
	        long end1 = System.currentTimeMillis();		
			long totalMillis = end1-start;
			String timeTaken = 	String.format("%d min, %d sec", 
				    TimeUnit.MILLISECONDS.toMinutes(totalMillis),
				    TimeUnit.MILLISECONDS.toSeconds(totalMillis) - 
				    TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(totalMillis))
				);
			System.out.println("extraProcessing part 2 (custom lineage) time: " + timeTaken);

		} catch (Exception ex) {
			System.out.println("exception found in extraProcessing" + ex.getMessage());
			ex.printStackTrace();
			if (doDebug && debugWriter !=null) {
				ex.printStackTrace(debugWriter);
				debugWriter.flush();
			}
			
		}
		

		if (doDebug && debugWriter !=null) {
			debugWriter.println("exiting extraProcessing()" );
			debugWriter.flush();
		}
		
		return;
	}
	

	/**
	 * get the connection name and database name (if known) 
	 * @param database - the database the wrapped table is from
	 * @param wrapper - the name of the wrapped object
	 * @return
	 */
	String getConnectionNameFromWrapper(String database, String wrapper) {
		if (doDebug && debugWriter !=null) {
			debugWriter.println("entering getConnectionNameFromWrapper(" + database + "," + wrapper +")" );
			debugWriter.flush();
		}
		String connectionName = "";

		// execute DESC VQL WRAPPER JDBC <db>.<wrapper>;
		String query="DESC VQL WRAPPER JDBC \"" + database + "\".\"" + wrapper + "\"";
//		PreparedStatement wrapperStmnt = null;
//		String wrapperQuery = "DESC VQL WRAPPER JDBC ";

        try {
			Statement st = connection.createStatement(); 
			ResultSet rs = st.executeQuery(query);
			while (rs.next()) {
//				System.out.println("\t\twrapper.....");
				String result = rs.getString("result");
//				System.out.println("result=\n" + result);
				
				// now we need to extract the DATASOURCENAME='<name>'
				int dsStart = result.indexOf("DATASOURCENAME=");
//				System.out.println("start pos=" + dsStart + " total length=" + result.length());
				int dsEnd = result.indexOf("\n", dsStart);
//				System.out.println("start end=" + dsEnd + " total length=" + result.length());
				if (dsStart>0 && dsEnd < result.length()) {
					connectionName = result.substring(dsStart+15, dsEnd);
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("getConnectionNameFromWrapper - Exception" );
				e.printStackTrace(debugWriter);
				debugWriter.flush();
			}

		} catch (Exception ex) {
			System.out.println("un-known exception caught" + ex.getMessage());
			ex.printStackTrace();
			if (doDebug && debugWriter !=null) {
				debugWriter.println("getConnectionNameFromWrapper - Exception" );
				ex.printStackTrace(debugWriter);
				debugWriter.flush();
			}

		}
		if (doDebug && debugWriter !=null) {
			debugWriter.println("exiting getConnectionNameFromWrapper(" + database + "," + wrapper +")" );
			debugWriter.flush();
		}
		
		return connectionName;
	}

	
	/**
	 * for base tables - we need to know what the datasource is
	 * wrappers are used in denodo to do this - e.g. DF, JDBC, WS ...
	 * @param catalog
	 * @param table
	 * @return
	 */
	protected String extractWrapperType(String catalog, String table) {
		// get the view sql
		String wrapperType="";
		// note - some tables have mixed case characters in the name - like ILMN.P2P/PurchaseOrderDetail_QV  (Hana)
		String viewSQL="desc vql view \"" + catalog + "\".\"" + table + "\"";
//		PreparedStatement wrapperStmnt = null;
//		String wrapperQuery = "DESC VQL WRAPPER JDBC ";
		String viewSqlStmnt="";
        try {
			Statement stViewSql = connection.createStatement(); 
			ResultSet rs = stViewSql.executeQuery(viewSQL);
			while (rs.next()) {
//				System.out.println("\t\twrapper.....");
//				System.out.println("view sql^^^^=" + viewSQL);
				viewSqlStmnt = rs.getString("result");
				
				// @ todo - extract only the view definition - denodo also includes all dependent objects
//				System.out.println("viewSQL=\n" + viewSqlStmnt);
				
				// @todo @important - get the wrapper typoe (DF JDBC WF ...
				
				int startPos = viewSqlStmnt.indexOf("CREATE WRAPPER");
//				System.out.println("create wrapper start: " + startPos);
				int endPos = viewSqlStmnt.indexOf(" ", startPos+15);
//				System.out.println("create wrapper end: " + endPos + " looking for<" + table + ">");
				wrapperType = viewSqlStmnt.substring(startPos+15, endPos).trim();
//				System.out.println("wrapper type raw: " + wrapperType);
				
				tableWrapperTypes.put(catalog + "." + table, wrapperType);

			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

		return wrapperType;
	}
	
	protected boolean initFiles() {
		super.initFiles();
		// assume working, until it is not
		boolean initialized=true;
		String outFolder = customMetadataFolder + "_lineage";
		String lineageFileName = outFolder + "/" + "denodo_lineage.csv";
		if (exportCustLineageInScanner) {
			lineageFileName = customMetadataFolder + "/" + "lineage.csv";
		}
		System.out.println("Step 3.1: initializing denodo specific files: " + lineageFileName);

		try { 
			// check that the folder exists - if not, create it
			File directory = new File(String.valueOf(outFolder));
			if(!directory.exists()){
				System.out.println("\tfolder: " + outFolder + " does not exist, creating it");
				directory.mkdir();
			}
			//			otherObjWriter = new CSVWriter(new FileWriter(otherObjectCsvName), ',', CSVWriter.NO_QUOTE_CHARACTER); 
			custLineageWriter = new CSVWriter(new FileWriter(lineageFileName)); 
			if (exportCustLineageInScanner) {
				custLineageWriter.writeNext(new String[]{"Association","From Connection","To Connection","From Object","To Object","com.infa.ldm.etl.ETLContext"});
			} else {
				custLineageWriter.writeNext(new String[]{"Association","From Connection","To Connection","From Object","To Object"});
			}

			System.out.println("\tDenodo Scanner Files initialized");
			
			
			if (doDebug) {
				debugWriter = new PrintWriter("denodoScanner_debug.txt");
			}
			

		} catch (IOException e1) { 
			initialized=false;
			e1.printStackTrace(); 
		} catch (Exception ex) {
			System.out.println("un-known exception caught" + ex.getMessage());
			ex.printStackTrace();
		} 

		return initialized;
	}

	/**
	 * close the files that were opened - ensures that any buffers are cleared
	 * @return
	 */
	protected boolean closeFiles() {
		System.out.println("closing denodo specific files");

		try { 
			custLineageWriter.close();
			if (debugWriter != null) {
				debugWriter.flush();
				debugWriter.close();
			}
		} catch (IOException e) { 
			e.printStackTrace(); 
			return false;
		} catch (Exception ex) {
			System.out.println("un-known exception caught" + ex.getMessage());
			ex.printStackTrace();
		} 
		
		
		System.out.println("expressions found/links created: " + expressionsFound + "/" + expressionLinks);
		System.out.println("lineage links written:  viewlevel=" + totalTableLineage + " columnLevel=" + totalColumnLineage);
		
		// we can';t call the superclass to close files - since it also zips 
		//		super.closeFiles();
		System.out.println("closing output files");

		try { 
			otherObjWriter.close(); 
			tableWriter.close();
			viewWriter.close();
			columnWriter.close(); 
			viewColumnWriter.close(); 
			linksWriter.close();
		} catch (IOException e) { 
			e.printStackTrace(); 
			return false;
		} 

		/**
		 * zip the files
		 */
//        List<String> srcFiles = Arrays.asList(
//        		customMetadataFolder + "/" + CATALOG_SCHEMA_FILENAME, 
//        		customMetadataFolder + "/" + TABLEVIEWS_FILENAME, 
//        		customMetadataFolder + "/" + VIEWS_FILENAME, 
//        		customMetadataFolder + "/" + VCOLUMN_FILENAME, 
//        		customMetadataFolder + "/" + COLUMN_FILENAME, 
//        		customMetadataFolder + "/" + LINKS_FILENAME
//        		);

        List<String> srcFiles = new ArrayList<String>();
        srcFiles.add(customMetadataFolder + "/" + CATALOG_SCHEMA_FILENAME); 
        srcFiles.add(customMetadataFolder + "/" + TABLEVIEWS_FILENAME); 
        srcFiles.add(customMetadataFolder + "/" + VIEWS_FILENAME); 
        srcFiles.add(customMetadataFolder + "/" + VCOLUMN_FILENAME); 
        srcFiles.add(customMetadataFolder + "/" + COLUMN_FILENAME); 
        srcFiles.add(customMetadataFolder + "/" + LINKS_FILENAME); 

        
        if (this.exportCustLineageInScanner) {
        	srcFiles.add(customMetadataFolder + "/" + "lineage.csv");
        }
        
        try {
        	System.out.println("creating zip file: " + customMetadataFolder + '/' + this.getClass().getSimpleName() + ".zip");
	        FileOutputStream fos = new FileOutputStream(customMetadataFolder + '/' + this.getClass().getSimpleName() + ".zip");
	        ZipOutputStream zipOut = new ZipOutputStream(fos);
	        for (String srcFile : srcFiles) {
	            File fileToZip = new File(srcFile);
	            FileInputStream fis;
					fis = new FileInputStream(fileToZip);
		            ZipEntry zipEntry = new ZipEntry(fileToZip.getName());
		            zipOut.putNextEntry(zipEntry);
		 
		            byte[] bytes = new byte[1024];
		            int length;
		            while((length = fis.read(bytes)) >= 0) {
		                zipOut.write(bytes, 0, length);
		            }
		            fis.close();
	        }
	        zipOut.close();
	        fos.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}



		
		return true;


	}

	/**
	 * flush all output files - for longer running processes
	 * @return true if successful
	 */
	protected boolean flushFiles() {
//		System.out.println("flushing denodo specific files");

		try { 
			otherObjWriter.flush(); 
			tableWriter.flush();
			viewWriter.flush();
			columnWriter.flush(); 
			viewColumnWriter.flush(); 
			linksWriter.flush();
			custLineageWriter.flush();
			if (debugWriter != null) {
				debugWriter.flush();
			}
		} catch (IOException e) { 
			e.printStackTrace(); 
			return false;
		} catch (Exception ex) {
			System.out.println("un-known exception caught" + ex.getMessage());
			ex.printStackTrace();
		} 
		

		return true;

	}

	
	/**
	 * for testing (outside/isolaged from the scanner
	 * @param theConnection
	 */
	protected void setConnection(Connection theConnection) {
		this.connection = theConnection;
		
	}

}
