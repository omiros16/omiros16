"""Amendment 1: how many independent trials do the 85 discovery trials amount to?"""

import numpy as np
import pandas as pd
from scipy.cluster.hierarchy import fcluster, linkage
from scipy.spatial.distance import squareform

from hypotheses import all_trials
from run_discovery import DISCOVERY, evaluate


def main() -> None:
    _, series = evaluate(DISCOVERY, all_trials())
    for s in DISCOVERY:
        m = pd.DataFrame({k[1]: v for k, v in series.items() if k[0] == s}).fillna(0)
        m = m.loc[:, m.std() > 0]
        corr = np.corrcoef(m.to_numpy().T)
        ev = np.clip(np.linalg.eigvalsh(corr), 0, None)
        li_ji = float(np.sum((ev >= 1) + (ev - np.floor(ev))))  # Li & Ji (2005)
        participation = float(ev.sum() ** 2 / (ev**2).sum())
        dist = 1 - np.abs(corr)
        np.fill_diagonal(dist, 0)
        clusters = len(set(fcluster(linkage(squareform(dist, checks=False), "average"), 0.5, "distance")))
        print(f"{s}: trials={m.shape[1]} Li-Ji={li_ji:.1f} participation={participation:.1f} clusters(<0.5)={clusters}")


if __name__ == "__main__":
    main()
