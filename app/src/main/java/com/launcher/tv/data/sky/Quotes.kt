package com.launcher.tv.data.sky

/** A line for the screensaver. */
data class Quote(val text: String, val author: String)

/**
 * Quotes for the screensaver, bundled so they work offline: well-known lines from public-domain
 * writers and proverbs, leaving out the famous misattributions that quote sites repeat.
 */
object Quotes {
    val all: List<Quote> = listOf(
        Quote("Be it ever so humble, there's no place like home.", "John Howard Payne"),
        Quote("Where we love is home, home that our feet may leave, but not our hearts.", "Oliver Wendell Holmes Sr."),
        Quote("There is nothing like staying at home for real comfort.", "Jane Austen"),
        Quote("The impediment to action advances action. What stands in the way becomes the way.", "Marcus Aurelius"),
        Quote("Waste no more time arguing about what a good man should be. Be one.", "Marcus Aurelius"),
        Quote("Very little is needed to make a happy life; it is all within yourself, in your way of thinking.", "Marcus Aurelius"),
        Quote("Dwell on the beauty of life. Watch the stars, and see yourself running with them.", "Marcus Aurelius"),
        Quote("It is not that we have a short time to live, but that we waste a lot of it.", "Seneca"),
        Quote("We suffer more often in imagination than in reality.", "Seneca"),
        Quote("Begin at once to live, and count each separate day as a separate life.", "Seneca"),
        Quote("If one advances confidently in the direction of his dreams, he will meet with a success unexpected in common hours.", "Henry David Thoreau"),
        Quote("Nothing great was ever achieved without enthusiasm.", "Ralph Waldo Emerson"),
        Quote("Adopt the pace of nature: her secret is patience.", "Ralph Waldo Emerson"),
        Quote("Write it on your heart that every day is the best day in the year.", "Ralph Waldo Emerson"),
        Quote("The only way to have a friend is to be one.", "Ralph Waldo Emerson"),
        Quote("Well done is better than well said.", "Benjamin Franklin"),
        Quote("Lost time is never found again.", "Benjamin Franklin"),
        Quote("One today is worth two tomorrows.", "Benjamin Franklin"),
        Quote("I am not afraid of storms, for I am learning how to sail my ship.", "Louisa May Alcott"),
        Quote("Courage is resistance to fear, mastery of fear, not absence of fear.", "Mark Twain"),
        Quote("The journey of a thousand miles begins with a single step.", "Lao Tzu"),
        Quote("Knowing others is intelligence; knowing yourself is true wisdom.", "Lao Tzu"),
        Quote("The unexamined life is not worth living.", "Socrates"),
        Quote("We know what we are, but know not what we may be.", "William Shakespeare"),
        Quote("Be not afraid of greatness.", "William Shakespeare"),
        Quote("Hope is the thing with feathers that perches in the soul.", "Emily Dickinson"),
        Quote("I dwell in Possibility.", "Emily Dickinson"),
        Quote("I exist as I am, that is enough.", "Walt Whitman"),
        Quote("To live is the rarest thing in the world. Most people exist, that is all.", "Oscar Wilde"),
        Quote("Every saint has a past, and every sinner has a future.", "Oscar Wilde"),
        Quote("Whatever is worth doing at all is worth doing well.", "Lord Chesterfield"),
        Quote("Rest is not idleness.", "John Lubbock"),
        Quote("The harder the conflict, the more glorious the triumph.", "Thomas Paine"),
        Quote("He who has a why to live can bear almost any how.", "Friedrich Nietzsche"),
        Quote("Without music, life would be a mistake.", "Friedrich Nietzsche"),
        Quote("If I have seen further, it is by standing on the shoulders of giants.", "Isaac Newton"),
        Quote("Knowledge is power.", "Francis Bacon"),
        Quote("The best way out is always through.", "Robert Frost"),
        Quote("Fall seven times, stand up eight.", "Japanese proverb"),
        Quote("A smooth sea never made a skilled sailor.", "English proverb"),
        Quote("Little by little, one travels far.", "Spanish proverb"),
        Quote("The best time to plant a tree was twenty years ago. The second best time is now.", "Proverb"),
    )

    /** Splits text into lines of at most [max] characters, at spaces. */
    fun wrap(text: String, max: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            line = when {
                line.isEmpty() -> word
                line.length + 1 + word.length <= max -> "$line $word"
                else -> { lines += line; word }
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }
}
