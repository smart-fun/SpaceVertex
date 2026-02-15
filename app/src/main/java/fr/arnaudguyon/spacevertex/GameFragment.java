/*
 * Copyright (c) 2016–2025 Arnaud GUYON
 * This source code is licensed under the MIT License.
 * See LICENSE file for details.
 */
package fr.arnaudguyon.spacevertex;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import fr.arnaudguyon.spacevertex.home.HomeActivity;
import fr.arnaudguyon.spacevertex.hud.BoostButton;
import fr.arnaudguyon.spacevertex.hud.DirectionController;
import fr.arnaudguyon.spacevertex.hud.FireButton;
import fr.arnaudguyon.spacevertex.hud.ThreadedTextView;
import fr.arnaudguyon.spacevertex.network.GameConnection;
import fr.arnaudguyon.spacevertex.network.GameDevice;
import fr.arnaudguyon.spacevertex.network.PackMsg;
import fr.arnaudguyon.spacevertex.objects.Scene;
import fr.arnaudguyon.spacevertex.objects.Ship;
import fr.arnaudguyon.spacevertex.objects.ShipLocal;
import fr.arnaudguyon.spacevertex.objects.ShipRemote;
import fr.arnaudguyon.spacevertex.sound.Sounds;

public class GameFragment extends Fragment implements GameConnection.MessageListener {

    private static final String TAG = "GameFragment";
    private static final int GAME_DURATION = BuildConfig.DEBUG ? 1 * 20 : 1 * 60;

    private View mView;
    private Scene mScene;
    private GameFragmentListener mListener;
    private ShipLocal mShipLocal;
    private ShipRemote mShipRemote;
    private GameConnection gameConnection;
    private GameDevice otherPlayer;
    private final Handler mHandler = new Handler();
    private int mDisplayedChrono = GAME_DURATION;
    private int mDisplayNotice = 3;
    private ViewHolder mViewHolder;
    private boolean mGameOver;
    private int mLocalScore;
    private int mLocalWins;
    private int mRemoteScore;
    private int mRemoteWins;

    public void initGameInformation(@NonNull Scene scene, @NonNull GameConnection gameConnection, @NonNull GameDevice otherPlayer, GameFragmentListener listener) {
        mScene = scene;
        this.gameConnection = gameConnection;
        this.otherPlayer = otherPlayer;
        mListener = listener;

        mScene.setGameConnection(gameConnection);

        gameConnection.addMessageListener(PackMsg.MsgType.SHIP_INFO, this);
        gameConnection.addMessageListener(PackMsg.MsgType.KILLED, this);
        gameConnection.addMessageListener(PackMsg.MsgType.GAME_RESTART, this);
        gameConnection.addMessageListener(PackMsg.MsgType.SHIP_FIRE, this);
        gameConnection.addMessageListener(PackMsg.MsgType.GAME_CHRONO, this);
        gameConnection.addMessageListener(PackMsg.MsgType.SHIP_SCORE, this);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.game_fragment, container, false);
        mViewHolder = new ViewHolder(mView);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (view.isInEditMode()) {
            return;
        }

        final ThreadedTextView leftChrono = mViewHolder.mLeftScore;
        final ThreadedTextView rightChrono = mViewHolder.mRightScore;
        final ThreadedTextView noticeTextView = mViewHolder.mCenterScore;
        Typeface font = Typeface.createFromAsset(getActivity().getAssets(), "trench100free.ttf");
        leftChrono.setTypeface(font);
        rightChrono.setTypeface(font);
        noticeTextView.setTypeface(font);
        mViewHolder.mNoticeView.setTypeface(font);
        noticeTextView.setText("");

        mViewHolder.mButtonsLayout.setVisibility(View.INVISIBLE);
        mViewHolder.mQuitButton.setTypeface(font);
        mViewHolder.mQuitButton.setOnClickListener(v -> onQuitButton());
        mViewHolder.mRestartButton.setTypeface(font);
        mViewHolder.mRestartButton.setOnClickListener(v -> onRestartButton());

        Scene scene = mScene;
        scene.setListener(createSceneListener());

        Context context = view.getContext();
        initShipsAndControls(context, scene, otherPlayer);
    }

    @Override
    public void onDestroyView() {

        Sounds sounds = Sounds.getInstance(getActivity());
        sounds.stopAll();

        if (mViewHolder != null) {
            final Scene scene = mScene;
            if (scene != null) {
                scene.setGameConnection(null);
            }
            mViewHolder = null;
        }
        super.onDestroyView();
    }

    private void initShipsAndControls(Context context, final Scene scene, GameDevice opponent) {

        if (mView == null) {
            return;
        }

        mShipLocal = new ShipLocal(context);
        mShipLocal.setOtherPlayer(opponent);

        DirectionController directionController = mViewHolder.mDirectionController;
        directionController.setListener(new DirectionController.DirectionListener() {
            @Override
            public void setDirection(float angleRadians) {
                if (!mGameOver && mShipLocal.isVisible()) {
                    mShipLocal.setWantedDirection(angleRadians);
                }
            }

            @Override
            public void setDirectionThrust(boolean on) {
                if (!mShipLocal.isVisible()) {
                    mShipLocal.setThrust(mScene, false);
                } else if (!mGameOver) {
                    mShipLocal.setThrust(mScene, on);
                }
            }
        });

        FireButton fireButton = mViewHolder.mFireButton;
        fireButton.setListener(() -> {
            if (!mGameOver && !explosionInProgress()) {
                int currentFrame = scene.getFrameNumberToSend();
                PackMsg packMsg = mShipLocal.shoot(currentFrame);
                if ((packMsg != null) && (gameConnection != null)) {
                    gameConnection.sendMessage(packMsg);    // Send Reliable FIRE message
                }
            }
        });

        BoostButton boostButton = mViewHolder.mBoostButton;
        boostButton.setListener(() -> {
            if (!mGameOver && !explosionInProgress() && mShipLocal.hasBoost()) {
                mShipLocal.boost();
                return true;
            } else {
                return false;
            }
        });

        mShipLocal.setButtons(fireButton, boostButton, directionController);
        scene.setLocalShip(mShipLocal);
        scene.startRendering();

        if (mShipRemote == null) {
            mShipRemote = new ShipRemote(context);
            scene.addObject(mShipRemote);
        }

        final boolean isServer = gameConnection.isServer();
        if (mShipLocal != null) {
            if (isServer) {
                mShipLocal.setPos(-400, 0);
                mShipLocal.setRotation((float) (Math.PI));
                mShipLocal.setWantedDirection((float) (Math.PI));
            }
            mShipLocal.setShipType(isServer ? Ship.ShipType.MOUSE : Ship.ShipType.CAT);
        }
        if (mShipRemote != null) {
            mShipRemote.setShipType(isServer ? Ship.ShipType.CAT : Ship.ShipType.MOUSE);
            mShipRemote.setReactorPower(Ship.ReactorPower.OFF, mShipLocal, mShipRemote);
            scene.setRemoteShip(mShipRemote);
            if (!isServer) {
                mShipRemote.setPos(-400, 0);
                mShipRemote.setRotation((float) (Math.PI / 2));
            }
        }

        mShipLocal.setWebSockets(gameConnection);
        mShipRemote.setWebSockets(gameConnection);
    }

    @Override
    public void onMessageReceived(@NonNull GameConnection gameConnection, @NonNull PackMsg packMsg) {
        if (!isAdded()) {
            return;
        }
        if (packMsg instanceof PackMsg.ShipInfo) {
            if (mScene != null) {
                mScene.onMessageReceived(packMsg);
            }
        } else if (packMsg instanceof PackMsg.ShipFire) {
            final Scene scene = mScene;
            if ((mShipRemote != null) && (scene != null)) {
                mShipRemote.fire(scene, (PackMsg.ShipFire) packMsg, mShipLocal);
            }
        } else if (packMsg instanceof PackMsg.ShipScore) {
            if ((mShipLocal != null) && (mShipLocal.getShipType() == Ship.ShipType.MOUSE)) {
                ++mLocalScore;
                Sounds.getInstance(getActivity()).playTic(true);
            } else {
                ++mRemoteScore;
                Sounds.getInstance(getActivity()).playTic(false);
            }
            updateScores();
        } else if (packMsg instanceof PackMsg.GameChrono) {
            PackMsg.GameChrono gameChrono = (PackMsg.GameChrono) packMsg;
            mDisplayedChrono = gameChrono.value;
            updateScores();
        } else if (packMsg instanceof PackMsg.Killed) {
            mHandler.post(() -> {
                if ((mShipRemote != null) && (mView != null)) {
                    Scene scene = mScene;
                    mShipRemote.setDestroyed(scene);
                }
            });
        } else if (packMsg instanceof PackMsg.GameRestart) {
            mView.post(() -> {
                hideChrono();
                restartAction();
            });
        }
    }

    private static class ViewHolder {

        public ThreadedTextView mLeftScore;
        public ThreadedTextView mRightScore;
        public ThreadedTextView mCenterScore;
        public ThreadedTextView mNoticeView;
        public DirectionController mDirectionController;
        public FireButton mFireButton;
        public BoostButton mBoostButton;
        public View mButtonsLayout;
        public TextView mQuitButton;
        public TextView mRestartButton;

        public ViewHolder(View view) {
            mLeftScore = view.findViewById(R.id.leftScore);
            mRightScore = view.findViewById(R.id.rightScore);
            mCenterScore = view.findViewById(R.id.centerScore);
            mNoticeView = view.findViewById(R.id.noticeView);
            mDirectionController = view.findViewById(R.id.directionController);
            mFireButton = view.findViewById(R.id.fireButton);
            mBoostButton = view.findViewById(R.id.boostButton);
            mButtonsLayout = view.findViewById(R.id.buttonsLayout);
            mQuitButton = mButtonsLayout.findViewById(R.id.quitButton);
            mRestartButton = mButtonsLayout.findViewById(R.id.restartButton);
        }
    }

    private void updateScores() {
        if ((mShipLocal != null) && (mShipRemote != null)) {

            if (mGameOver) {
                // Already Game Over, do nothing
            } else if (mDisplayedChrono == 0) {    // WIN / LOSE

                mGameOver = true;
                updateDisplayedChrono();

                HomeActivity activity = (HomeActivity) getActivity();
                if (activity != null) {
                    activity.onGameOver(true);
                    changeControlsVisibility(false);
                }

                if (mLocalScore > mRemoteScore) {           // You Win
                    ++mLocalWins;
                    Sounds.getInstance(getActivity()).playWin();
                    String format = getString(R.string.notice_win);
                    String text = String.format(format, mLocalWins, mRemoteWins);
                    updateNotice(text, getResources().getColor(R.color.svGreen));
                } else if (mLocalScore < mRemoteScore) {    // You Lose
                    ++mRemoteWins;
                    Sounds.getInstance(getActivity()).playLose();
                    String format = getString(R.string.notice_lose);
                    String text = String.format(format, mLocalWins, mRemoteWins);
                    updateNotice(text, getResources().getColor(R.color.svRed));
                    displayQuitRestartButtons();
                } else {
                    Sounds.getInstance(getActivity()).playWin();
                    String format = getString(R.string.notice_draw);
                    String text = String.format(format, mLocalWins, mRemoteWins);
                    updateNotice(text, getResources().getColor(R.color.svGreyText));
                    if(mShipLocal.hasBoost()){
                        displayQuitRestartButtons();
                    }
                }
            } else if (mDisplayNotice > 0) {    // NOTICE / RULES
                --mDisplayNotice;
                boolean isMouse = (mShipLocal.getShipType() == Ship.ShipType.MOUSE);
                int notice = isMouse ? R.string.notice_mouse : R.string.notice_cat;
                updateNotice(notice, mShipLocal.getShipType().getShipColor());
            } else {    // CHRONO
                hideNotice();
                updateDisplayedChrono();
            }

            // SCORES (added space to avoid the 4 to be cut...)
            String leftText = " " + mLocalScore;
            mViewHolder.mLeftScore.setText(leftText);
            mViewHolder.mLeftScore.setTextColor(mShipLocal.getShipType().getShipColor());

            String rightText = " " + mRemoteScore;
            mViewHolder.mRightScore.setText(rightText);
            mViewHolder.mRightScore.setTextColor(mShipRemote.getShipType().getShipColor());

            if (mGameOver) {
                mShipLocal.setRotation(0);
                mShipLocal.setWantedDirection(0);
                mShipLocal.setThrust(mScene, false);
                mShipLocal.setReactorPower(Ship.ReactorPower.ON, mShipLocal, mShipRemote);
                mShipRemote.setRotation(0);
                mShipRemote.setReactorPower(Ship.ReactorPower.ON, mShipLocal, mShipRemote);
            }
        }
    }

    private void updateDisplayedChrono() {
        ThreadedTextView scoreView = mViewHolder.mCenterScore;
        scoreView.setVisibility(View.VISIBLE);
        int minutes = mDisplayedChrono / 60;
        int seconds = mDisplayedChrono - (minutes * 60);
        String scoreText = (seconds >= 10) ? (minutes + ":" + seconds) : (minutes + ":0" + seconds);
        scoreView.setText(scoreText);
        scoreView.setTextColor(0xFFffffff);//mShipLocal.getShipType().getShipColor());
    }

    private void updateNotice(int resId, int color) {
        String text = getString(resId);
        updateNotice(text, color);
    }

    private void updateNotice(String text, int color) {
        ThreadedTextView notice = mViewHolder.mNoticeView;
        notice.setText(text);
        notice.setTextColor(color);
    }

    private void hideChrono() {
        mViewHolder.mCenterScore.setText("");
    }

    private void hideNotice() {
        mViewHolder.mNoticeView.setText("");
    }

    private void displayQuitRestartButtons() {
        mView.post(() -> {
            mViewHolder.mButtonsLayout.setVisibility(View.VISIBLE); // Losers choose to restart
        });
    }

    private void onQuitButton() {
        mViewHolder.mButtonsLayout.setVisibility(View.INVISIBLE);
        if (mListener != null) {
            mListener.onQuitGameFragment();
        }
    }

    private void onRestartButton() {
        mViewHolder.mButtonsLayout.setVisibility(View.INVISIBLE);
        hideChrono();
        PackMsg.GameRestart gameRestart = new PackMsg.GameRestart(otherPlayer);
        gameConnection.sendMessage(gameRestart);
        mView.postDelayed(() -> restartAction(), 50); // little delay
    }

    private void restartAction() {
        mDisplayedChrono = GAME_DURATION;
        mLocalScore = 0;
        mRemoteScore = 0;
        mGameOver = false;
        mScene.invertRoles();
        HomeActivity activity = (HomeActivity) getActivity();
        if (activity != null) {
            activity.onGameOver(false);
            changeControlsVisibility(true);
        }
    }

    private void sendWsChrono(int chronoValue) {
        if (gameConnection == null) {
            return;
        }
        PackMsg.GameChrono gameChrono = new PackMsg.GameChrono(chronoValue, otherPlayer);
        gameConnection.sendMessage(gameChrono);
    }

    private boolean explosionInProgress() {
        if ((mShipLocal != null) && mShipLocal.isExploding()) {
            return true;
        }
        if ((mShipRemote != null) && mShipRemote.isExploding()) {
            return true;
        }
        return false;
    }

    private Scene.SceneListener createSceneListener() {
        return new Scene.SceneListener() {

            @Override
            public void onChronoChanged(int chrono) {
                if (getActivity() == null) {
                    return;
                }

                // Don't change Chrono while there is a Kill in progress
                if (explosionInProgress()) {
                    return;
                }

                if ((gameConnection != null) && (gameConnection.isServer())) {

                    if (!mGameOver && (mDisplayNotice == 0)) {  // Start Chrono when Notice is removed
                        --mDisplayedChrono;
                    }
                    sendWsChrono(mDisplayedChrono);

                    updateScores();
                }

            }

            @Override
            public void increaseMouseScore() {

                if (getActivity() == null) {
                    return;
                }

                // Don't change Mouse Score while there is a Kill in progress or Notice is displayed
                if (explosionInProgress() || (mDisplayNotice != 0)) {
                    return;
                }

                if (!mGameOver && (gameConnection != null) && (gameConnection.isServer())) {
                    if (mShipLocal.getShipType() == Ship.ShipType.CAT) {
                        ++mRemoteScore;
                        Sounds.getInstance(getActivity()).playTic(false);
                        PackMsg.ShipScore mouseRemoteScore = new PackMsg.ShipScore(PackMsg.ShipScore.Scorer.MOUSE, mRemoteScore, otherPlayer);
                        gameConnection.sendMessage(mouseRemoteScore);
                    } else {
                        ++mLocalScore;
                        Sounds.getInstance(getActivity()).playTic(true);
                        PackMsg.ShipScore mouseLocalScore = new PackMsg.ShipScore(PackMsg.ShipScore.Scorer.MOUSE, mLocalScore, otherPlayer);
                        gameConnection.sendMessage(mouseLocalScore);
                    }
                    updateScores();
                }
            }

            @Override
            public void onRolesHaveChanged() {
                Log.i(TAG, "onRolesHaveChanged()");
                mDisplayNotice = 3;
            }

            @Override
            public void sendMessage(PackMsg message) {
                if (gameConnection != null) {
                    gameConnection.sendMessage(message);
                }
            }

            @Override
            public boolean isGameOver() {
                return mGameOver;
            }

        };
    }

    private void changeControlsVisibility(final boolean visible) {

        if (visible) {
            mViewHolder.mNoticeView.setText("");
        }

        mHandler.post(() -> {
            if (visible) {
                mViewHolder.mDirectionController.setVisibility(View.VISIBLE);
                if ((mShipLocal != null) && mShipLocal.hasBoost()) {
                    mViewHolder.mBoostButton.setVisibility(View.VISIBLE);
                }
                if ((mShipLocal != null) && mShipLocal.canKill()) {
                    mViewHolder.mFireButton.setVisibility(View.VISIBLE);
                }
            } else {
                mViewHolder.mDirectionController.setVisibility(View.GONE);
                mViewHolder.mBoostButton.setVisibility(View.GONE);
                mViewHolder.mFireButton.setVisibility(View.GONE);
            }
        });
    }

    public interface GameFragmentListener {
        void onQuitGameFragment();
    }
}
