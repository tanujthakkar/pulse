# Published metrics in Pulse 0.1.17

Pulse computes the numbers below from stored heart rate, motion, and a body profile the person types in. Nothing here is a vendor score, and none of the coefficients are taken from a closed product.

Measured means the value is a count or an average of stored samples. Estimated means a published formula or a Pulse reading of a public description stands between the samples and the number on screen.

## VO2 max — estimated

Uth, Sørensen, Overgaard, and Pedersen, 2004, estimated maximal oxygen uptake as:

`VO2max = 15.3 × HRmax / HRrest`

Pulse uses the night's resting heart rate for `HRrest`. `HRmax` is not measured. It is the Tanaka, Monahan, and Seals 2001 age prediction:

`HRmax = 208 − 0.7 × age`

The card stays `--` until age is saved and a night has a resting heart rate. Higher is treated as better. The original Uth fit was a small adult sample, so this is an estimate, not a lab test.

- Uth N, Sørensen H, Overgaard K, Pedersen PK. Estimation of VO2max from the ratio between HRmax and HRrest — the Heart Rate Ratio Method. Eur J Appl Physiol. 2004;91:111-115.
- Tanaka H, Monahan KD, Seals DR. Age-predicted maximal heart rate revisited. J Am Coll Cardiol. 2001;37:153-156.

## Calories — estimated

Resting expenditure for the day is Mifflin–St Jeor, in kcal/day:

- Men: `10 × kg + 6.25 × cm − 5 × age + 5`
- Women: `10 × kg + 6.25 × cm − 5 × age − 161`

Activity expenditure is Keytel and colleagues, 2005. Their equations predict kJ/min during submaximal exercise:

- Men: `−55.0969 + 0.6309 × HR + 0.1988 × kg + 0.2017 × age`
- Women: `−20.4022 + 0.4472 × HR − 0.1263 × kg + 0.074 × age`

Pulse does not add that whole rate on top of resting expenditure. It adds only the difference between the rate at the day's mean heart rate and the rate at resting heart rate, converted with `1 kcal = 4.184 kJ`, for the minutes that have a heart-rate sample. Age and weight cancel in that difference, which is expected. The card still needs sex, age, height, and weight because the resting term needs all four. Until the profile is saved the card is `--` and More explains what to enter. A day with a profile but no heart-rate samples shows the resting term alone.

Calories have no better or worse direction. The percent change on the detail screen stays neutral.

- Mifflin MD, St Jeor ST, Hill LA, Scott BJ, Daugherty SA, Koh YO. A new predictive equation for resting energy expenditure in healthy individuals. Am J Clin Nutr. 1990;51:241-247.
- Keytel LR, Goedecke JH, Noakes TD, Hiiloskorpi H, Laukkanen R, van der Merwe L, Lambert EV. Prediction of energy expenditure from heart rate monitoring during submaximal exercise. J Sports Sci. 2005;23:289-297.

## Sleep performance — estimated

Public sleep-coach descriptions define sleep performance as time asleep divided by sleep need. Pulse uses the stored sleep-sample count as time asleep and the sleep need below. The home ring is a different number: it is still the sample count divided by a fixed 8-hour reference, capped at 100%. The detail page uses sleep need.

## Sleep need — estimated

Public descriptions split sleep need into a personal baseline, an addition for recent physical strain, and an addition for sleep debt. Pulse implements those three pieces with its own bounds:

- Baseline is the median of up to 14 earlier main-sleep durations, once at least three exist. Before that it is 8 hours.
- Strain adds up to 60 minutes, linear in the previous day's approximate 0–21 strain.
- Debt adds half of the shortfall against that baseline over the previous four nights, capped at 90 minutes. The shortfall for an earlier night is `max(0, baseline − slept)` using the same baseline.

The legend on the sleep page is Healthy minimum, Recent strain, and Sleep debt. All three are estimates.

## Sleep consistency — estimated

Public descriptions score how stable bed and wake times are. The published Sleep Regularity Index (Phillips et al., 2017) needs a 24-hour epoch label Pulse does not store. Pulse instead takes the mean absolute deviation of bed time and of wake time from the person's own median, averages those two deviations, and scales them by 3 hours:

`score = 100 × (1 − deviation / 180 minutes)`

The score is blank until three nights exist. Higher is better. The card draws the last five nights as bed-to-wake bars against dashed lines at the median bed and median wake.

- Phillips AJK, Clerx WM, O'Brien CS, et al. Irregular sleep/wake patterns are associated with poorer academic performance and delayed circadian and sleep/wake timing. Sci Rep. 2017;7:3216.

## Sleep efficiency — measured ratio

Efficiency is stored sleep samples divided by the in-bed span of the episode, including samples that are not sleep. That is the usual time-asleep / time-in-bed ratio. Higher is better.

## Wake events — measured count, estimated rule

A wake event is a run of non-sleep samples lasting at least 30 seconds, after sleep has started and before sleep resumes. The final wake-up is the end of the night and is not counted. The 30-second rule is Pulse's, because a public disturbance count does not publish its detector.

## Sleep stress — estimated

Public descriptions of sleep stress use heart rate and heart-rate variability through the night. No public formula gives the 0–3 scale. Pulse maps each minute to 0–3 from heart-rate elevation above resting, about one point per 8 bpm, and adds one point when the minute is mostly awake. High is 2 or above, medium is 1 up to 2, and low is below 1. The headline percent is the share of minutes that are high. Lower is better. The chart is labeled as an estimate.

## Sleep stages — estimated, or not available

Stored history has no stage channel. Pulse does not invent a night of stages when it cannot see enough heart rate.

When a night has at least 30 minutes of samples, each minute is classified from heart rate and the sleep motion bit:

- Awake, when most of the minute is not sleep, or heart rate is more than 12 bpm above resting.
- Deep, the lowest quarter of sleeping-minute heart rates.
- REM, the highest quarter of sleeping-minute heart rates that also move by at least 4 bpm inside the minute.
- Light, the remaining sleeping minutes.

Restorative sleep on the page is the deep minutes plus the REM minutes. The section says the stages are estimated. A shorter night, or a night with no heart rate, shows "Estimated stages are not available for this night." instead of a number.

This is a heuristic. It is not the Beattie 2017 neural network and it is not a clinical staging.

## Dashboard sleep debt — measured against a fixed reference

The My Dashboard sleep-debt card remains `slept − 8 hours`. That 8-hour reference is not the personal sleep need. The sleep page's debt term is the personal one described above. The two charts are labeled for the definition they use. Lower debt is better.

## Metric detail

Every dashboard card, and each weekly chart on the sleep page, opens one detail screen.

- Week: one point per day. The x-axis label is the weekday and the day of the month.
- Month: one point per week, the average of the days in that week. The label is the Monday of that week, as a month/day date.
- 6 months: one point per day that has a value, drawn as a line.
- The shaded band is the mean of the plotted points in the selected range, plus or minus one population standard deviation (`Σ (x − mean)² / n`). It is hidden when fewer than two points are plotted. This is the mean ± 1 SD band, not the interquartile range.
- The number above the chart is the average of the days in the selected range.
- The number below is the percent change of that average against the previous range of the same length. Green means the metric's own direction improved. Yellow means it worsened. Resting heart rate, average heart rate, sleep debt, and high sleep stress improve when they fall. HRV, steps, VO2 max, sleep performance, efficiency, consistency, restorative sleep, and time asleep improve when they rise. Calories are neutral and stay gray.

## Profile

Age, sex, height, and weight live in the app's private preferences, not in the history database. VO2 max and calories show `--` and point at More until those fields are saved. The profile is not sent off the phone.
