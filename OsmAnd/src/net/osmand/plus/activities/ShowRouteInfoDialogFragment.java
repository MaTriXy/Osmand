package net.osmand.plus.activities;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.FileProvider;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.utils.Utils;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.plus.GPXUtilities;
import net.osmand.plus.GPXUtilities.GPXFile;
import net.osmand.plus.GPXUtilities.GPXTrackAnalysis;
import net.osmand.plus.GPXUtilities.Track;
import net.osmand.plus.GPXUtilities.TrkSegment;
import net.osmand.plus.GPXUtilities.WptPt;
import net.osmand.plus.IconsCache;
import net.osmand.plus.OsmAndFormatter;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.mapcontextmenu.other.MapRouteInfoMenu;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.views.TurnPathHelper;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.Algorithms;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class ShowRouteInfoDialogFragment extends DialogFragment {

	public static final String TAG = "ShowRouteInfoDialogFragment";

	private RoutingHelper helper;
	private View view;
	private ListView listView;
	private RouteInfoAdapter adapter;
	private GPXFile gpx;

	public ShowRouteInfoDialogFragment() {
	}

	private OsmandApplication getMyApplication() {
		return (OsmandApplication) getActivity().getApplication();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		boolean isLightTheme = getMyApplication().getSettings().OSMAND_THEME.get() == OsmandSettings.OSMAND_LIGHT_THEME;
		int themeId = isLightTheme ? R.style.OsmandLightTheme : R.style.OsmandDarkTheme;
		setStyle(STYLE_NO_FRAME, themeId);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
							 Bundle savedInstanceState) {
		final OsmandApplication app = getMyApplication();
		helper = app.getRoutingHelper();

		view = inflater.inflate(R.layout.route_info_layout, container, false);

		Toolbar toolbar = (Toolbar) view.findViewById(R.id.toolbar);
		toolbar.setNavigationIcon(getMyApplication().getIconsCache().getThemedIcon(R.drawable.abc_ic_ab_back_mtrl_am_alpha));
		toolbar.setNavigationContentDescription(R.string.shared_string_close);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				dismiss();
			}
		});

		((ImageView) view.findViewById(R.id.distance_icon))
				.setImageDrawable(app.getIconsCache().getThemedIcon(R.drawable.ic_action_polygom_dark));
		((ImageView) view.findViewById(R.id.time_icon))
				.setImageDrawable(app.getIconsCache().getThemedIcon(R.drawable.ic_action_time_span));

		buildMenuButtons();

		listView = (ListView) view.findViewById(android.R.id.list);
		listView.setBackgroundColor(getResources().getColor(
				app.getSettings().isLightContent() ? R.color.ctx_menu_info_view_bg_light
						: R.color.ctx_menu_info_view_bg_dark));

		View topShadowView = inflater.inflate(R.layout.list_shadow_header, listView, false);
		listView.addHeaderView(topShadowView, null, false);
		View bottomShadowView = inflater.inflate(R.layout.list_shadow_footer, listView, false);
		listView.addFooterView(bottomShadowView, null, false);

		adapter = new RouteInfoAdapter(helper.getRouteDirections());
		listView.setAdapter(adapter);
		listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				if (position < 2) {
					return;
				}
				RouteDirectionInfo item = adapter.getItem(position - 2);
				Location loc = helper.getLocationFromRouteDirection(item);
				if(loc != null){
					MapRouteInfoMenu.directionInfo = position - 2;
					OsmandSettings settings = getMyApplication().getSettings();
					settings.setMapLocationToShow(loc.getLatitude(),loc.getLongitude(),
							Math.max(13, settings.getLastKnownMapZoom()),
							new PointDescription(PointDescription.POINT_TYPE_MARKER, item.getDescriptionRoutePart() + " " + getTimeDescription(item)),
							false, null);
					MapActivity.launchMapActivityMoveToTop(getActivity());
					dismiss();
				}
			}
		});

		int dist = helper.getLeftDistance();
		int time = helper.getLeftTime();
		int hours = time / (60 * 60);
		int minutes = (time / 60) % 60;
		((TextView)view.findViewById(R.id.distance)).setText(OsmAndFormatter.getFormattedDistance(dist, app));
		StringBuilder timeStr = new StringBuilder();
		if (hours > 0) {
			timeStr.append(hours).append(" ").append(getString(R.string.osmand_parking_hour)).append(" ");
		}
		if (minutes > 0) {
			timeStr.append(minutes).append(" ").append(getString(R.string.osmand_parking_minute));
		}
		((TextView)view.findViewById(R.id.time)).setText(timeStr);

		view.findViewById(R.id.go_button).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				MapActivity activity = (MapActivity)getActivity();
				if (activity != null) {
					activity.getMapLayers().getMapControlsLayer().startNavigation();
					dismiss();
				}
			}
		});

		makeGpx();
		if (!gpx.isEmpty()) {
			View headerView = inflater.inflate(R.layout.route_info_header, null);
			buildHeader(headerView);
			listView.addHeaderView(headerView);
		}

		return view;
	}

	private void makeGpx() {
		double lastHeight = -1;
		gpx = new GPXFile();
		List<RouteSegmentResult> route = helper.getRoute().getLeftRoute();
		if (route != null) {
			Track track = new Track();
			for (RouteSegmentResult res : route) {
				TrkSegment seg = new TrkSegment();
				int inc = res.getStartPointIndex() < res.getEndPointIndex() ? 1 : -1;
				int indexnext = res.getStartPointIndex();
				for (int index = res.getStartPointIndex(); index != res.getEndPointIndex(); ) {
					index = indexnext;
					indexnext += inc;
					LatLon l = res.getPoint(index);
					WptPt point = new WptPt();
					point.lat = l.getLatitude();
					point.lon = l.getLongitude();
					float[] vls = res.getObject().calculateHeightArray();
					if (vls != null && index * 2 + 1 < vls.length) {
						point.ele = vls[2 * index + 1];
						//point.desc = (res.getObject().getId() >> (BinaryInspector.SHIFT_ID )) + " " + index;
						lastHeight = vls[2 * index + 1];
					} else if (lastHeight > 0) {
						point.ele = lastHeight;
					}
					seg.points.add(point);
				}
				track.segments.add(seg);
			}
			gpx.tracks.add(track);
		}
	}

	private void buildHeader(View headerView) {
		OsmandApplication app = getMyApplication();
		LineChart mChart = (LineChart) headerView.findViewById(R.id.chart);
		GPXUtilities.setupGPXChart(app, mChart, 4);

		GPXTrackAnalysis analysis = gpx.getAnalysis(0);
		GPXUtilities.setGPXChartData(app, mChart, analysis, Utils.getSDKInt() >= 18
				? R.drawable.line_chart_fade_blue : R.color.gpx_time_span_color);

		((TextView) headerView.findViewById(R.id.average_text))
				.setText(OsmAndFormatter.getFormattedAlt(analysis.avgElevation, app));

		String min = OsmAndFormatter.getFormattedAlt(analysis.minElevation, app);
		String max = OsmAndFormatter.getFormattedAlt(analysis.maxElevation, app);
		((TextView) headerView.findViewById(R.id.range_text))
				.setText(min + " " + max);

		String asc = OsmAndFormatter.getFormattedAlt(analysis.diffElevationUp, app);
		String desc = OsmAndFormatter.getFormattedAlt(analysis.diffElevationDown, app);
		((TextView) headerView.findViewById(R.id.descent_text)).setText(desc);
		((TextView) headerView.findViewById(R.id.ascent_text)).setText(asc);

		((ImageView) headerView.findViewById(R.id.average_icon))
				.setImageDrawable(app.getIconsCache().getThemedIcon(R.drawable.ic_action_altitude_average));
		((ImageView) headerView.findViewById(R.id.range_icon))
				.setImageDrawable(app.getIconsCache().getThemedIcon(R.drawable.ic_action_altitude_average));
		((ImageView) headerView.findViewById(R.id.descent_icon))
				.setImageDrawable(app.getIconsCache().getThemedIcon(R.drawable.ic_action_altitude_descent));
		((ImageView) headerView.findViewById(R.id.ascent_icon))
				.setImageDrawable(app.getIconsCache().getThemedIcon(R.drawable.ic_action_altitude_ascent));
	}

	private void buildMenuButtons() {
		IconsCache iconsCache = getMyApplication().getIconsCache();
		ImageButton printRoute = (ImageButton) view.findViewById(R.id.print_route);
		printRoute.setImageDrawable(iconsCache.getThemedIcon(R.drawable.ic_action_gprint_dark));
		printRoute.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				print();
			}
		});

		ImageButton saveRoute = (ImageButton) view.findViewById(R.id.save_as_gpx);
		saveRoute.setImageDrawable(iconsCache.getThemedIcon(R.drawable.ic_action_gsave_dark));
		saveRoute.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				MapActivityActions.createSaveDirections(getActivity(), helper).show();
			}
		});

		ImageButton shareRoute = (ImageButton) view.findViewById(R.id.share_as_gpx);
		shareRoute.setImageDrawable(iconsCache.getThemedIcon(R.drawable.ic_action_gshare_dark));
		shareRoute.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				final GPXFile gpx = helper.generateGPXFileWithRoute();
				final Uri fileUri = Uri.fromFile(new File(gpx.path));
				File dir = new File(getActivity().getCacheDir(), "share");
				if (!dir.exists()) {
					dir.mkdir();
				}
				File dst = new File(dir, "route.gpx");
				try {
					FileWriter fw = new FileWriter(dst);
					GPXUtilities.writeGpx(fw, gpx, getMyApplication());
					fw.close();
					final Intent sendIntent = new Intent();
					sendIntent.setAction(Intent.ACTION_SEND);
					sendIntent.putExtra(Intent.EXTRA_TEXT, Html.fromHtml(generateHtml(adapter,
							helper.getGeneralRouteInformation()).toString()));
					sendIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_route_subject));
					sendIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
					sendIntent.putExtra(
							Intent.EXTRA_STREAM,
							FileProvider.getUriForFile(getActivity(),
									getActivity().getPackageName() + ".fileprovider", dst));
					sendIntent.setType("text/plain");
					startActivity(sendIntent);
				} catch (IOException e) {
					// Toast.makeText(getActivity(), "Error sharing favorites: " + e.getMessage(),
					// Toast.LENGTH_LONG).show();
					e.printStackTrace();
				}
			}
		});
	}

	public static void showDialog(FragmentManager fragmentManager) {
		ShowRouteInfoDialogFragment fragment = new ShowRouteInfoDialogFragment();
		fragment.show(fragmentManager, TAG);
	}

	class RouteInfoAdapter extends ArrayAdapter<RouteDirectionInfo> {
		public class CumulativeInfo {
			public int distance;
			public int time;

			public CumulativeInfo() {
				distance = 0;
				time = 0;
			}
		}

		private final int lastItemIndex;
		private boolean light;

		RouteInfoAdapter(List<RouteDirectionInfo> list) {
			super(getActivity(), R.layout.route_info_list_item, list);
			lastItemIndex = list.size() - 1;
			this.setNotifyOnChange(false);
			light = getMyApplication().getSettings().isLightContent();
		}


		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View row = convertView;
			if (row == null) {
				LayoutInflater inflater =
						(LayoutInflater) getMyApplication().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				row = inflater.inflate(R.layout.route_info_list_item, parent, false);
			}
			RouteDirectionInfo model = getItem(position);
			TextView label = (TextView) row.findViewById(R.id.description);
			TextView distanceLabel = (TextView) row.findViewById(R.id.distance);
			TextView timeLabel = (TextView) row.findViewById(R.id.time);
			TextView cumulativeDistanceLabel = (TextView) row.findViewById(R.id.cumulative_distance);
			TextView cumulativeTimeLabel = (TextView) row.findViewById(R.id.cumulative_time);
			ImageView icon = (ImageView) row.findViewById(R.id.direction);
			row.findViewById(R.id.divider).setVisibility(position == getCount() - 1 ? View.INVISIBLE : View.VISIBLE);

			TurnPathHelper.RouteDrawable drawable = new TurnPathHelper.RouteDrawable(getResources(), true);
			drawable.setColorFilter(new PorterDuffColorFilter(light ? getResources().getColor(R.color.icon_color) : Color.WHITE, PorterDuff.Mode.SRC_ATOP));
			drawable.setRouteType(model.getTurnType());
			icon.setImageDrawable(drawable);

			label.setText(String.valueOf(position + 1) + ". " + model.getDescriptionRoutePart());
			if (model.distance > 0) {
				distanceLabel.setText(OsmAndFormatter.getFormattedDistance(
						model.distance, getMyApplication()));
				timeLabel.setText(getTimeDescription(model));
				row.setContentDescription(label.getText() + " " + timeLabel.getText()); //$NON-NLS-1$
			} else {
				if (label.getText().equals(String.valueOf(position + 1) + ". ")) {
					label.setText(String.valueOf(position + 1) + ". " + getString((position != lastItemIndex) ? R.string.arrived_at_intermediate_point : R.string.arrived_at_destination));
				}
				distanceLabel.setText(""); //$NON-NLS-1$
				timeLabel.setText(""); //$NON-NLS-1$
				row.setContentDescription(""); //$NON-NLS-1$
			}
			CumulativeInfo cumulativeInfo = getRouteDirectionCumulativeInfo(position);
			cumulativeDistanceLabel.setText(OsmAndFormatter.getFormattedDistance(
					cumulativeInfo.distance, getMyApplication()));
			cumulativeTimeLabel.setText(Algorithms.formatDuration(cumulativeInfo.time, getMyApplication().accessibilityEnabled()));
			return row;
		}

		public CumulativeInfo getRouteDirectionCumulativeInfo(int position) {
			CumulativeInfo cumulativeInfo = new CumulativeInfo();
			for (int i = 0; i < position; i++) {
				RouteDirectionInfo routeDirectionInfo = (RouteDirectionInfo) getItem(i);
				cumulativeInfo.time += routeDirectionInfo.getExpectedTime();
				cumulativeInfo.distance += routeDirectionInfo.distance;
			}
			return cumulativeInfo;
		}
	}

	private String getTimeDescription(RouteDirectionInfo model) {
		final int timeInSeconds = model.getExpectedTime();
		return Algorithms.formatDuration(timeInSeconds, getMyApplication().accessibilityEnabled());
	}

	void print() {
		File file = generateRouteInfoHtml(adapter, helper.getGeneralRouteInformation());
		if (file.exists()) {
			Uri uri = Uri.fromFile(file);
			Intent browserIntent;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) { // use Android Print Framework
				browserIntent = new Intent(getActivity(), PrintDialogActivity.class)
						.setDataAndType(uri, "text/html");
			} else { // just open html document
				browserIntent = new Intent(Intent.ACTION_VIEW).setDataAndType(
						uri, "text/html");
			}
			startActivity(browserIntent);
		}
	}

	private File generateRouteInfoHtml(RouteInfoAdapter routeInfo, String title) {
		File file = null;
		if (routeInfo == null) {
			return file;
		}

		final String fileName = "route_info.html";
		StringBuilder html = generateHtmlPrint(routeInfo, title);
		FileOutputStream fos = null;
		try {
			file = getMyApplication().getAppPath(fileName);
			fos = new FileOutputStream(file);
			fos.write(html.toString().getBytes("UTF-8"));
			fos.flush();
		} catch (IOException e) {
			file = null;
			e.printStackTrace();
		} finally {
			if (fos != null) {
				try {
					fos.close();
				} catch (Exception e) {
					file = null;
					e.printStackTrace();
				}
			}
		}

		return file;
	}
	
	private StringBuilder generateHtml(RouteInfoAdapter routeInfo, String title) {
		StringBuilder html = new StringBuilder();
		if (!TextUtils.isEmpty(title)) {
			html.append("<h1>");
			html.append(title);
			html.append("</h1>");
		}
		final String NBSP = "&nbsp;";
		final String BR = "<br>";
		for (int i = 0; i < routeInfo.getCount(); i++) {
			RouteDirectionInfo routeDirectionInfo = (RouteDirectionInfo) routeInfo.getItem(i);
			StringBuilder sb = new StringBuilder();
			sb.append(OsmAndFormatter.getFormattedDistance(routeDirectionInfo.distance, getMyApplication()));
			sb.append(", ").append(NBSP);
			sb.append(getTimeDescription(routeDirectionInfo));
			String distance = sb.toString().replaceAll("\\s", NBSP);
			String description = routeDirectionInfo.getDescriptionRoutePart();
			html.append(BR);
			html.append("<p>" + String.valueOf(i + 1) + ". " + NBSP + description + NBSP + "(" + distance + ")</p>");
		}
		return html;
	}

	private StringBuilder generateHtmlPrint(RouteInfoAdapter routeInfo, String title) {
		StringBuilder html = new StringBuilder();
		html.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">");
		html.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\">");
		html.append("<head>");
		html.append("<title>Route info</title>");
		html.append("<meta http-equiv=\"Content-type\" content=\"text/html; charset=utf-8\" />");
		html.append("<style>");
		html.append("table, th, td {");
		html.append("border: 1px solid black;");
		html.append("border-collapse: collapse;}");
		html.append("th, td {");
		html.append("padding: 5px;}");
		html.append("</style>");
		html.append("</head>");
		html.append("<body>");


		if (!TextUtils.isEmpty(title)) {
			html.append("<h1>");
			html.append(title);
			html.append("</h1>");
		}
		html.append("<table style=\"width:100%\">");
		final String NBSP = "&nbsp;";
		final String BR = "<br>";
		for (int i = 0; i < routeInfo.getCount(); i++) {
			RouteDirectionInfo routeDirectionInfo = (RouteDirectionInfo) routeInfo.getItem(i);
			html.append("<tr>");
			StringBuilder sb = new StringBuilder();
			sb.append(OsmAndFormatter.getFormattedDistance(routeDirectionInfo.distance, getMyApplication()));
			sb.append(", ");
			sb.append(getTimeDescription(routeDirectionInfo));
			String distance = sb.toString().replaceAll("\\s", NBSP);
			html.append("<td>");
			html.append(distance);
			html.append("</td>");
			String description = routeDirectionInfo.getDescriptionRoutePart();
			html.append("<td>");
			html.append(String.valueOf(i + 1) + ". " + description);
			html.append("</td>");
			RouteInfoAdapter.CumulativeInfo cumulativeInfo = routeInfo.getRouteDirectionCumulativeInfo(i);
			html.append("<td>");
			sb = new StringBuilder();
			sb.append(OsmAndFormatter.getFormattedDistance(cumulativeInfo.distance, getMyApplication()));
			sb.append(" - ");
			sb.append(OsmAndFormatter.getFormattedDistance(cumulativeInfo.distance + routeDirectionInfo.distance,
					getMyApplication()));
			sb.append(BR);
			sb.append(Algorithms.formatDuration(cumulativeInfo.time, getMyApplication().accessibilityEnabled()));
			sb.append(" - ");
			sb.append(Algorithms.formatDuration(cumulativeInfo.time + routeDirectionInfo.getExpectedTime(),
					getMyApplication().accessibilityEnabled()));
			String cumulativeTimeAndDistance = sb.toString().replaceAll("\\s", NBSP);
			html.append(cumulativeTimeAndDistance);
			html.append("</td>");
			html.append("</tr>");
		}
		html.append("</table>");
		html.append("</body>");
		html.append("</html>");
		return html;
	}

}
