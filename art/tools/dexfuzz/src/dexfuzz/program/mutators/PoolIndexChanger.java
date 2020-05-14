/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dexfuzz.program.mutators;

import dexfuzz.Log;
import dexfuzz.MutationStats;
import dexfuzz.program.MInsn;
import dexfuzz.program.MutatableCode;
import dexfuzz.program.Mutation;
import dexfuzz.rawdex.formats.ContainsPoolIndex;
import dexfuzz.rawdex.formats.ContainsPoolIndex.PoolIndexKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PoolIndexChanger extends CodeMutator {
  /**
   * Every CodeMutator has an AssociatedMutation, representing the
   * mutation that this CodeMutator can perform, to allow separate
   * generateMutation() and applyMutation() phases, allowing serialization.
   */
  public static class AssociatedMutation extends Mutation {
    public int poolIndexInsnIdx;
    public int newPoolIndex;

    @Override
    public String getString() {
      StringBuilder builder = new StringBuilder();
      builder.append(poolIndexInsnIdx).append(" ");
      builder.append(newPoolIndex);
      return builder.toString();
    }

    @Override
    public void parseString(String[] elements) {
      poolIndexInsnIdx = Integer.parseInt(elements[2]);
      newPoolIndex = Integer.parseInt(elements[3]);
    }
  }

  // The following two methods are here for the benefit of MutationSerializer,
  // so it can create a CodeMutator and get the correct associated Mutation, as it
  // reads in mutations from a dump of mutations.
  @Override
  public Mutation getNewMutation() {
    return new AssociatedMutation();
  }

  public PoolIndexChanger() { }

  public PoolIndexChanger(Random rng, MutationStats stats, List<Mutation> mutations) {
    super(rng, stats, mutations);
    likelihood = 30;
  }

  // A cache that should only exist between generateMutation() and applyMutation(),
  // or be created at the start of applyMutation(), if we're reading in mutations from
  // a file.
  private List<MInsn> poolIndexInsns = null;

  private void generateCachedPoolIndexInsns(MutatableCode mutatableCode) {
    if (poolIndexInsns != null) {
      return;
    }

    poolIndexInsns = new ArrayList<MInsn>();
    for (MInsn mInsn : mutatableCode.getInstructions()) {
      if (mInsn.insn.info.format instanceof ContainsPoolIndex) {
        poolIndexInsns.add(mInsn);
      }
    }
  }

  @Override
  protected boolean canMutate(MutatableCode mutatableCode) {
    // Remember what kinds of pool indices we see.
    List<PoolIndexKind> seenKinds = new ArrayList<PoolIndexKind>();

    for (MInsn mInsn : mutatableCode.getInstructions()) {
      if (mInsn.insn.info.format instanceof ContainsPoolIndex) {

        ContainsPoolIndex containsPoolIndex =
            (ContainsPoolIndex)mInsn.insn.info.format;

        PoolIndexKind newPoolIndexKind =
            containsPoolIndex.getPoolIndexKind(mInsn.insn.info);

        seenKinds.add(newPoolIndexKind);
      }
    }

    // Now check that there exists a kind such that the max pool index for
    // the kind is greater than 1 (i.e., something can be changed)
    if (!seenKinds.isEmpty()) {

      for (PoolIndexKind kind : seenKinds) {
        int numPoolIndices = mutatableCode.program.getTotalPoolIndicesByKind(kind);
        if (numPoolIndices > 1) {
          return true;
        }
      }

      Log.debug("Method does not contain any insns that index into a const pool size > 1");
      return false;
    }

    Log.debug("Method contains no instructions that index into the constant pool.");
    return false;
  }

  @Override
  protected Mutation generateMutation(MutatableCode mutatableCode) {
    generateCachedPoolIndexInsns(mutatableCode);

    int poolIndexInsnIdx = 0;
    boolean found = false;

    int oldPoolIndex = 0;
    int newPoolIndex = 0;
    int maxPoolIndex = 0;

    // Pick a random instruction.
    while (!found) {
      poolIndexInsnIdx = rng.nextInt(poolIndexInsns.size());
      MInsn poolIndexInsn = poolIndexInsns.get(poolIndexInsnIdx);

      found = true;

      ContainsPoolIndex containsPoolIndex =
          (ContainsPoolIndex)poolIndexInsn.insn.info.format;

      // Get the pool index.
      oldPoolIndex = containsPoolIndex.getPoolIndex(poolIndexInsn.insn);
      newPoolIndex = oldPoolIndex;

      // Get the largest pool index available for the provided kind of pool index.
      PoolIndexKind poolIndexKind =
          containsPoolIndex.getPoolIndexKind(poolIndexInsn.insn.info);
      maxPoolIndex = mutatableCode.program.getTotalPoolIndicesByKind(poolIndexKind);

      if (maxPoolIndex <= 1) {
        found = false;
      }
    }

    // Get a new pool index.
    while (newPoolIndex == oldPoolIndex) {
      newPoolIndex = rng.nextInt(maxPoolIndex);
    }

    AssociatedMutation mutation = new AssociatedMutation();
    mutation.setup(this.getClass(), mutatableCode);
    mutation.poolIndexInsnIdx = poolIndexInsnIdx;
    mutation.newPoolIndex = newPoolIndex;
    return mutation;
  }

  @Override
  protected void applyMutation(Mutation uncastMutation) {
    // Cast the Mutation to our AssociatedMutation, so we can access its fields.
    AssociatedMutation mutation = (AssociatedMutation) uncastMutation;
    MutatableCode mutatableCode = mutation.mutatableCode;

    generateCachedPoolIndexInsns(mutatableCode);

    MInsn poolIndexInsn = poolIndexInsns.get(mutation.poolIndexInsnIdx);

    ContainsPoolIndex containsPoolIndex =
        (ContainsPoolIndex) poolIndexInsn.insn.info.format;

    int oldPoolIndex = containsPoolIndex.getPoolIndex(poolIndexInsn.insn);

    Log.info("Changed pool index " + oldPoolIndex + " to " + mutation.newPoolIndex
        + " in " + poolIndexInsn);

    stats.incrementStat("Changed constant pool index");

    // Set the new pool index.
    containsPoolIndex.setPoolIndex(poolIndexInsn.insn, mutation.newPoolIndex);

    // Clear cache.
    poolIndexInsns = null;
  }
}
