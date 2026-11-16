package app.openring.core;

/**
 * The lines of Today's "Your day" card ({@link DayLine}), one list per branch of its decision tree and one list that fits
 * any day ({@link #ANY}), used when a branch's own lines were all shown in the last 30 days. Written for Open Ring (owner's
 * wish of 16 November), in two voices: quiet self accountability, and warmth, connection and honesty. Plain English, short,
 * no dashes as punctuation, never medical; {@code DayLineTest} checks every rule. A line's id is its branch key and its
 * place in the list ("debt.3"), used only for the display memory, so lines can be added at the end of a list freely.
 */
public final class DayLineBank {
    private DayLineBank() {}

    /** Lines for any day, also a day with signs of strain or without fresh data: gentle and general. */
    public static final String[] ANY = {
        "You cannot control the night. You can control the next hour.",
        "Be honest with yourself. It is the start of every good change.",
        "Breathe out slowly. Then decide.",
        "Tell someone how you really are today.",
        "Your days add up in your body. Make today a kind one to add.",
        "Drink some water. Look at the sky. Then carry on.",
        "Small acts of care are never wasted.",
        "Listen more than you speak today, to others and to yourself.",
        "You are allowed to change your plans when your body changes its mind.",
        "Today you only need to do today.",
        "Comparison steals energy. Your numbers are only about you.",
        "Gratitude is a quiet kind of rest.",
        "Choose one thing to let go of today.",
        "The kindest voice in your head should also be the loudest.",
        "Rest and effort are partners, not rivals.",
        "Look up from the numbers. Life is happening around them.",
        "Today will not decide everything. It only adds to it.",
        "Hug someone, or call them. Both count.",
        "The people around you notice how you feel. Let them help.",
        "Kindness to yourself is not indulgence. It is upkeep.",
        "Ask what would make tonight's sleep easier, then do that.",
        "Being honest about a bad day is how good ones begin.",
        "Slow down enough to notice that you are here.",
        "Every evening is a chance to start the next day well.",
        "Nobody is measuring your worth. Not even this app.",
        "You are more than any number on this screen.",
    };

    /** The data is not up to date: the lines never pretend to know the day. */
    static final String[] STALE = {
        "I do not know how you slept. You do. Start there.",
        "No new numbers today. Your own sense of the day still counts.",
        "Without data, ask the oldest instrument there is: how do you feel?",
        "The numbers can wait. Notice your breathing for a moment instead.",
        "Not knowing is honest. Guessing would not be.",
        "Old numbers describe an old day. Today is still unwritten.",
        "Sync when it suits you. Until then, trust what your body tells you.",
        "A gap in the data is not a gap in you.",
        "Today is unmeasured. Some of the best days are.",
        "The picture here is out of date. The one out of the window is not.",
    };

    /** Temperature up with resting heart rate up or HRV down: take it easy, no diagnosis. */
    static final String[] STRAIN = {
        "Your body is working on something. Give it room today.",
        "Slow is a choice, not a failure. Today it may be the wise one.",
        "Cancel one thing you can. The rest of the week will thank you.",
        "Rest is not only earned. Sometimes it is simply needed, like today.",
        "Listen early, and the body rarely has to shout.",
        "Push less, notice more. That is strength too.",
        "Let someone know you are taking it slow. Saying it makes it easier.",
        "A warm drink, an early night, fewer promises. Small things count.",
        "You are not behind. You are looking after the one body you have.",
        "Be as patient with yourself today as you would be with a friend.",
        "Strength includes knowing when to sit down.",
    };

    /** A short night or a big sleep debt. */
    static final String[] DEBT = {
        "Sleep is a debt that only sleep repays. Tonight is a good night to start.",
        "You cannot borrow tomorrow's energy without paying it back.",
        "An earlier night is a small promise. Keep it.",
        "Tired days make loud opinions. Hold yours a little more loosely today.",
        "Nobody gets a medal for the hours they did not sleep.",
        "Choose one thing to do well today, and let the rest be good enough.",
        "Go to bed before the evening talks you out of it.",
        "Be gentle with the people around you. They may be tired too.",
        "The short night is done. What you do with tonight is still yours.",
        "Say no to one thing today, so you can say yes to sleep.",
        "Sleep is not lost time. It is where the day gets put away.",
    };

    /** Readiness back to good after low days. */
    static final String[] RECOVERED = {
        "You came back. That is what bodies do when you let them.",
        "The low days were not wasted. They were the way back.",
        "Notice what helped. Do more of that.",
        "Recovery is quiet work. Today you can see it.",
        "Spend the new energy slowly. It is easier to keep than to win back.",
        "You did not force this. You allowed it. Remember the difference.",
        "Thank whoever made room for you to rest. Tell them it worked.",
        "Good days feel better after hard ones. Let this one feel good.",
        "Back on your feet. No need to sprint to prove it.",
        "Patience paid. It usually does, just not on our schedule.",
    };

    /** A badge earned on this day. */
    static final String[] BADGE = {
        "A small win, honestly earned. Let yourself enjoy it.",
        "The badge is the receipt. The habit is what you paid for.",
        "Celebrate a little, then carry on. That is how habits stay.",
        "You did that. Nobody else could have done it for you.",
        "Tell someone about it. Good news grows when it is shared.",
        "Proof that small choices add up. Keep making them.",
        "Earned, not given. That is why it feels good.",
        "Today counts, not because of the badge, but because of what you did.",
        "Notice the pride. You are allowed to feel it.",
        "One good day is a spark. A few more and it is a fire.",
    };

    /** Good nights in a row. */
    static final String[] NIGHTS = {
        "Night after night, you are building something nobody can buy.",
        "Consistency is not exciting. It is quietly powerful.",
        "Good sleep is not luck. It is a promise you keep each evening.",
        "Whatever you are doing in the evenings, it is working.",
        "Protect the routine. It is protecting you.",
        "Rested people are kinder. Spend some of that kindness today.",
        "A run of good nights is a gift to everyone who meets you.",
        "Keep the evening simple. Simple is what got you here.",
        "The body remembers rhythm. You are teaching it a good one.",
        "Some old sayings about sleep are old because they are true.",
    };

    /** The activity or step goal reached days in a row. */
    static final String[] ACTIVE = {
        "Every walk is a vote for the person you want to be. You keep voting.",
        "Motivation fades. Habits carry on. Yours are carrying you.",
        "You do not need a big day. You need another ordinary good one.",
        "Every day you showed up made the next one easier.",
        "Walk with someone today. Company makes the steps lighter.",
        "Keep it playful. The streak serves you, not the other way round.",
        "Strong is something you practise, not something you are.",
        "Your legs have been listening. Thank them with a stretch.",
        "Rest days belong to a streak too. Do not fear one.",
        "Nobody was watching, and you did it anyway. That is character.",
    };

    /** A lot of phone use before sleep or in the night. */
    static final String[] SCREEN = {
        "The phone will still be there in the morning. So will the news.",
        "The last hour before sleep shapes the next eight. Guard it.",
        "Tonight, leave the phone in another room. See what happens.",
        "Scrolling feels like rest. It rarely is.",
        "What were you looking for last night? Maybe it was not on the screen.",
        "Call someone instead of scrolling. A voice beats a feed.",
        "Your attention is precious. Spend less of it on strangers at midnight.",
        "A book, a bath, a conversation. Anything that does not glow.",
        "The bed is for sleeping. Let the phone sleep somewhere else.",
        "Be honest: did the last half hour on the phone make you happier?",
        "Dim the screen, then dim the evening. Sleep follows the dark.",
    };

    /** The day after hard training. */
    static final String[] REST = {
        "Training breaks you down. Rest is where you get stronger.",
        "A rest day is part of the plan, not a break from it.",
        "Hard yesterday, easy today. That is most of the secret.",
        "Let the muscles do their quiet work today.",
        "Stretch, walk, eat well. Recovery is training too.",
        "The discipline to rest is rarer than the discipline to push.",
        "Yesterday you gave. Today you receive.",
        "Proud of yesterday? Show it by not undoing it today.",
        "Slow walks count. So do long breakfasts in good company.",
        "Strong people rest without guilt. Practise that too.",
    };

    /** A logged period day, when the period tracker shows: kind lines only. */
    static final String[] PERIOD = {
        "Be kind to yourself today. It is a fair thing to ask.",
        "Your body is doing a lot of quiet work. Let the day be softer.",
        "Comfort is not a luxury. Take the warm blanket.",
        "Lower the bar if you need to. You still count at the lower bar.",
        "It is fine to say you are not at your best. Honesty makes room for help.",
        "Move gently if it feels good. Rest if that feels better.",
        "Your rhythm is yours. No need to match anyone else's today.",
        "Treat yourself this week the way you would treat a good friend.",
        "Some days ask for less. Giving less is allowed.",
        "Rest, warmth, water, patience. Simple things carry a long way.",
    };

    /** Readiness (or sleep) under good, nothing more specific. */
    static final String[] LOW = {
        "A low day is still a day. Do the small things well.",
        "You do not have to feel ready to do something gently.",
        "Lower the pace, keep the direction.",
        "Not every day is for climbing. Some are for walking on the flat.",
        "Be honest about your energy today. It makes better plans.",
        "Ask for help with one thing. That is not weakness, it is good sense.",
        "Small and steady still gets there.",
        "The body asks quietly at first. You are hearing it early.",
        "Do less, and do it with care.",
        "Some days the best plan is the one you can actually keep.",
    };

    /** A strong day: readiness (or sleep) optimal. */
    static final String[] GOOD = {
        "You have energy to spare. Spend some of it on someone else.",
        "Good days are for the thing you have been putting off.",
        "Feel strong? Use it well, not just a lot.",
        "Enjoy this one. You helped make it.",
        "A good day is a chance, not a duty. Pick what matters.",
        "Strength is best used gently. Lift someone up today.",
        "Say the honest thing you have been holding back. Today you can carry it.",
        "Make it a day you would be glad to remember.",
        "The body is ready. What does the rest of you want?",
        "Use the good days to build what the hard days will need.",
    };

    /** An ordinary Saturday or Sunday. */
    static final String[] WEEKEND = {
        "No alarm owns you today. Wake with the light if you can.",
        "Weekends are for people, not for catching up on email.",
        "Do something slow on purpose.",
        "Rest is not something you earn by Friday. It is yours anyway.",
        "Call someone you have been meaning to call.",
        "A long walk with no destination. Try it.",
        "Keep your bedtime close to the week's. Monday will thank you.",
        "Let the day be a little unplanned.",
        "Make breakfast for someone, or let someone make it for you.",
        "Do one thing only because you enjoy it.",
    };

    /** A plain weekday. */
    static final String[] ORDINARY = {
        "Most of life happens on ordinary days. Make this one count a little.",
        "Nothing special in the numbers. That is often what good looks like.",
        "Steady is underrated. You are steady today.",
        "Ordinary days are where habits are built.",
        "Do the next right thing. Then the one after.",
        "No drama in the data. Put your attention where it matters.",
        "A plain day is a good day to be kind for no reason.",
        "Show up, do your part, go to bed on time. Quietly heroic.",
        "Small choices today shape how tonight will feel.",
        "Notice one good thing today and say it out loud.",
    };

    /** The lists by branch, in the order of {@link DayLine}'s branch numbers. */
    static final String[][] BY_BRANCH = {STALE, STRAIN, DEBT, RECOVERED, BADGE, NIGHTS, ACTIVE, SCREEN, REST, PERIOD, LOW, GOOD, WEEKEND,
        ORDINARY};

    /** The lines of branch {@code b} (a {@link DayLine} branch number), or {@link #ANY} for {@link DayLine#ANY}. */
    public static String[] of(int b) {
        return b == DayLine.ANY ? ANY : BY_BRANCH[b];
    }
}
