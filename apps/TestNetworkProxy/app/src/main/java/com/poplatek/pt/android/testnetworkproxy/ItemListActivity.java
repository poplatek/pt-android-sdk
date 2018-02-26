package com.poplatek.pt.android.testnetworkproxy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import com.poplatek.pt.android.testnetworkproxy.dummy.DummyContent;

import java.util.List;

/**
 * An activity representing a list of Items. This activity
 * has different presentations for handset and tablet-size devices. On
 * handsets, the activity presents a list of items, which when touched,
 * lead to a {@link ItemDetailActivity} representing
 * item details. On tablets, the activity presents the list of items and
 * item details side-by-side using two vertical panes.
 */
public class ItemListActivity extends AppCompatActivity {
    /**
     * Run RFCOMM/JSONPOS network proxy test.
     */
    private static boolean runTestOnce = true;
    private static final String RFCOMM_TARGET_MAC = null;  // null is autodetect; "00:07:80:23:FA:C9";
    private static Toast previousToast = null;
    private boolean isResumed = false;

    @Override
    public void onPause() {
        super.onPause();
        Log.d("TEST", "ItemListActivity paused");
        isResumed = false;
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d("TEST", "ItemListActivity resumed");
        isResumed = true;
    }

    void checkRunNetworkProxyTest() {
        if (!runTestOnce) {
            return;
        }
        runTestOnce = false;

        final Activity activity = this;
        Thread t = new Thread(new Runnable() {
            public void run() {
                String deviceMac = RFCOMM_TARGET_MAC;
                Log.i("TEST", "bluetooth test");
                com.poplatek.pt.android.jsonpos.BluetoothNetworkProxyRunner btRunner =
                    new com.poplatek.pt.android.jsonpos.BluetoothNetworkProxyRunner(deviceMac);
                btRunner.setDebugStatusCallback(new com.poplatek.pt.android.jsonpos.BluetoothNetworkProxyRunner.DebugStatusCallback() {
                    public void updateStatus(String text) throws Exception {
                        // Toasts as a trivial example of a DebugStatusCallback integration.
                        final String finalText = text;
                        activity.runOnUiThread(new Runnable() {
                            public void run() {
                                synchronized (activity) {
                                    if (previousToast != null) {
                                        previousToast.cancel();
                                        previousToast = null;
                                    }
                                    if (isResumed) {
                                        Toast t = Toast.makeText(activity, finalText, Toast.LENGTH_LONG);
                                        previousToast = t;
                                        t.show();
                                    }
                                }
                            }
                        });
                    }
                });
                btRunner.runProxyLoop();
            }
        });
        t.start();
    }

    /**
     * Whether or not the activity is in two-pane mode, i.e. running on a tablet
     * device.
     */
    private boolean mTwoPane;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Minimal hack to run test once in the background.
        checkRunNetworkProxyTest();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_item_list);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle(getTitle());

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null).show();
            }
        });

        if (findViewById(R.id.item_detail_container) != null) {
            // The detail container view will be present only in the
            // large-screen layouts (res/values-w900dp).
            // If this view is present, then the
            // activity should be in two-pane mode.
            mTwoPane = true;
        }

        View recyclerView = findViewById(R.id.item_list);
        assert recyclerView != null;
        setupRecyclerView((RecyclerView) recyclerView);
    }

    private void setupRecyclerView(@NonNull RecyclerView recyclerView) {
        recyclerView.setAdapter(new SimpleItemRecyclerViewAdapter(this, DummyContent.ITEMS, mTwoPane));
    }

    public static class SimpleItemRecyclerViewAdapter
        extends RecyclerView.Adapter<SimpleItemRecyclerViewAdapter.ViewHolder> {

        private final ItemListActivity mParentActivity;
        private final List<DummyContent.DummyItem> mValues;
        private final boolean mTwoPane;
        private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                DummyContent.DummyItem item = (DummyContent.DummyItem)view.getTag();
                if (mTwoPane) {
                    Bundle arguments = new Bundle();
                    arguments.putString(ItemDetailFragment.ARG_ITEM_ID, item.id);
                    ItemDetailFragment fragment = new ItemDetailFragment();
                    fragment.setArguments(arguments);
                    mParentActivity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.item_detail_container, fragment)
                    .commit();
                } else {
                    Context context = view.getContext();
                    Intent intent = new Intent(context, ItemDetailActivity.class);
                    intent.putExtra(ItemDetailFragment.ARG_ITEM_ID, item.id);

                    context.startActivity(intent);
                }
            }
        };

        SimpleItemRecyclerViewAdapter(ItemListActivity parent,
                                      List<DummyContent.DummyItem> items,
                                      boolean twoPane) {
            mValues = items;
            mParentActivity = parent;
            mTwoPane = twoPane;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_list_content, parent, false);

            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            holder.mIdView.setText(mValues.get(position).id);
            holder.mContentView.setText(mValues.get(position).content);

            holder.itemView.setTag(mValues.get(position));
            holder.itemView.setOnClickListener(mOnClickListener);
        }

        @Override
        public int getItemCount() {
            return mValues.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView mIdView;
            final TextView mContentView;

            ViewHolder(View view) {
                super(view);
                mIdView = (TextView) view.findViewById(R.id.id_text);
                mContentView = (TextView) view.findViewById(R.id.content);
            }
        }
    }
}
